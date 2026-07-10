package com.hyperbrain.prioritizer;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.CycleType;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.prioritizer.domain.port.out.PriorityStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the JDBC read side of the Prioritizer against a real PostgreSQL: the recursive
 * {@code parent_cycle_id} walk that yields the graded-alignment contexts, and the deadline-anchored
 * raw urgency (Comité 2026-07-09). Black-box: only the public {@link PriorityStateRepository} is
 * exercised; the domain grading itself is unit-tested.
 */
@IntegrationTest
@DisplayName("JdbcPriorityStateRepository — graded alignment CTE + deadline-anchored urgency")
class JdbcPriorityStateRepositoryIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PriorityStateRepository repository;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("recursive walk: a task 2 hops under an active MCI reports the MCI ancestor at distance 2")
    void graded_context_walks_up_to_active_mci() {
        UUID mci = insertCycle("MCI", "ACTIVE", null);
        UUID objective = insertCycle("OBJECTIVE", "ACTIVE", mci);
        UUID project = insertCycle("PROJECT", "ACTIVE", objective);

        Map<UUID, CycleAlignmentContext> contexts = repository.findAlignmentContexts(USER);

        CycleAlignmentContext projectContext = contexts.get(project);
        assertThat(projectContext.ownType()).isEqualTo(CycleType.PROJECT);
        assertThat(projectContext.activeAncestors())
            .anySatisfy(link -> {
                assertThat(link.ancestorType()).isEqualTo(CycleType.MCI);
                assertThat(link.distance()).isEqualTo(2);
            });
    }

    @Test
    @DisplayName("an inactive MCI ancestor is not reported (only ACTIVE ancestors align)")
    void completed_mci_is_not_an_active_ancestor() {
        UUID mci = insertCycle("MCI", "COMPLETED", null);
        UUID project = insertCycle("PROJECT", "ACTIVE", mci);

        CycleAlignmentContext context = repository.findAlignmentContexts(USER).get(project);

        assertThat(context.activeAncestors())
            .noneMatch(link -> link.ancestorType() == CycleType.MCI);
    }

    @Test
    @DisplayName("minimum distance is kept when a cycle reaches the same ancestor by two paths")
    void keeps_minimum_distance_per_ancestor() {
        // A diamond: leaf -> a -> mci and leaf -> mci directly is not possible (single parent),
        // so model two active ancestors of the same type at different depths on one chain.
        UUID mciFar = insertCycle("MCI", "ACTIVE", null);
        UUID mciNear = insertCycle("MCI", "ACTIVE", mciFar);
        UUID leaf = insertCycle("PROJECT", "ACTIVE", mciNear);

        CycleAlignmentContext context = repository.findAlignmentContexts(USER).get(leaf);

        // Both MCIs collapse to a single MCI link at the minimum distance (1).
        assertThat(context.activeAncestors())
            .filteredOn(link -> link.ancestorType() == CycleType.MCI)
            .hasSize(1)
            .allSatisfy(link -> assertThat(link.distance()).isEqualTo(1));
    }

    @Test
    @DisplayName("a cycle in the parent graph (self-referential loop) is walked without looping forever")
    void cycle_guard_stops_infinite_walk() {
        UUID a = insertCycle("PROJECT", "ACTIVE", null);
        UUID b = insertCycle("OBJECTIVE", "ACTIVE", a);
        // Close the loop: a's parent becomes b.
        jdbcTemplate.update("UPDATE core_cycle SET parent_cycle_id = ? WHERE id = ?", b, a);

        Map<UUID, CycleAlignmentContext> contexts = repository.findAlignmentContexts(USER);

        // The query terminates and every cycle is present.
        assertThat(contexts).containsKeys(a, b);
    }

    @Test
    @DisplayName("urgency is 0 while the deadline is beyond the 7-day horizon")
    void urgency_zero_outside_horizon() {
        UUID task = insertTask("Far task", OffsetDateTime.now().plusDays(30));

        double urgency = urgencyOf(task);

        assertThat(urgency).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("urgency ramps to ~5 at the deadline")
    void urgency_five_at_deadline() {
        UUID task = insertTask("Due now", OffsetDateTime.now().plusSeconds(2));

        double urgency = urgencyOf(task);

        assertThat(urgency).isCloseTo(5.0, within(0.05));
    }

    @Test
    @DisplayName("an old task with a far deadline is NOT permanently urgent (the v1 bug is gone)")
    void old_task_far_deadline_is_not_urgent() {
        UUID task = insertTask("Old but not due", OffsetDateTime.now().plusDays(14));
        // Backdate creation far into the past — the old creation-anchored ramp would pin this at 5/6.
        jdbcTemplate.update("UPDATE core_executable SET created_at = ? WHERE id = ?",
            OffsetDateTime.now().minusDays(365), task);

        double urgency = urgencyOf(task);

        assertThat(urgency).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("an overdue task rises above 5 up to the cap 6")
    void overdue_task_exceeds_five_capped_at_six() {
        UUID task = insertTask("Overdue", OffsetDateTime.now().minusDays(3));

        double urgency = urgencyOf(task);

        assertThat(urgency).isGreaterThan(5.0);
        assertThat(urgency).isLessThanOrEqualTo(6.0);
    }

    @Test
    @DisplayName("a task with no deadline carries no urgency signal")
    void no_deadline_no_urgency() {
        UUID task = insertTask("No deadline", null);

        double urgency = urgencyOf(task);

        assertThat(urgency).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("saveScores persists priority_score, raw urgency_score and priority_computed_at")
    void save_scores_persists_all_three_columns() {
        UUID task = insertTask("Scored", null);

        Set<UUID> changed = repository.saveScores(List.of(new PriorityScore(task, 0.73, 4.5, 0.6)));

        assertThat(changed).containsExactly(task);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT priority_score, urgency_score, priority_computed_at FROM core_executable WHERE id = ?",
            task);
        assertThat((Double) row.get("priority_score")).isCloseTo(0.73, within(1e-9));
        assertThat((Double) row.get("urgency_score")).isCloseTo(4.5, within(1e-9)); // raw 0-6, not normalized
        assertThat(row.get("priority_computed_at")).isNotNull();
    }

    @Test
    @DisplayName("saveScores diffs against the persisted score: an unchanged row is not rewritten and not reported")
    void save_scores_skips_unchanged_rows() {
        UUID task = insertTask("Stable", null);
        repository.saveScores(List.of(new PriorityScore(task, 0.50, 3.0, 0.4)));
        OffsetDateTime firstStamp = computedAt(task);

        // Same score within epsilon -> no change reported, clock not bumped
        Set<UUID> unchanged = repository.saveScores(List.of(new PriorityScore(task, 0.50, 3.0, 0.4)));
        assertThat(unchanged).isEmpty();
        assertThat(computedAt(task)).isEqualTo(firstStamp);

        // A real move -> reported and re-stamped
        Set<UUID> moved = repository.saveScores(List.of(new PriorityScore(task, 0.80, 3.0, 0.4)));
        assertThat(moved).containsExactly(task);
        assertThat(computedAt(task)).isAfterOrEqualTo(firstStamp);
    }

    @Test
    @DisplayName("saveScores of a non-existent row is a no-op (nothing to update, nothing reported)")
    void save_scores_absent_row_is_noop() {
        Set<UUID> changed = repository.saveScores(
            List.of(new PriorityScore(UUID.randomUUID(), 0.9, 5.0, 0.5)));

        assertThat(changed).isEmpty();
    }

    @Test
    @DisplayName("findFactors returns one executable's factors; a system-generated row carries no priority")
    void find_factors_single_row() {
        UUID task = insertTask("One", OffsetDateTime.now().plusSeconds(2));

        assertThat(repository.findFactors(task)).hasValueSatisfying(f -> {
            assertThat(f.executableId()).isEqualTo(task);
            assertThat(f.urgencyRaw()).isCloseTo(5.0, within(0.05));
        });

        jdbcTemplate.update("UPDATE core_executable SET system_generated = true WHERE id = ?", task);
        assertThat(repository.findFactors(task)).isEmpty();
    }

    @Test
    @DisplayName("findAlignmentContext resolves a single cycle's active ancestors")
    void find_alignment_context_single_cycle() {
        UUID mci = insertCycle("MCI", "ACTIVE", null);
        UUID project = insertCycle("PROJECT", "ACTIVE", mci);

        assertThat(repository.findAlignmentContext(project)).hasValueSatisfying(ctx -> {
            assertThat(ctx.ownType()).isEqualTo(CycleType.PROJECT);
            assertThat(ctx.activeAncestors())
                .anySatisfy(link -> {
                    assertThat(link.ancestorType()).isEqualTo(CycleType.MCI);
                    assertThat(link.distance()).isEqualTo(1);
                });
        });
    }

    private OffsetDateTime computedAt(UUID taskId) {
        return jdbcTemplate.queryForObject(
            "SELECT priority_computed_at FROM core_executable WHERE id = ?",
            OffsetDateTime.class, taskId);
    }

    private double urgencyOf(UUID taskId) {
        return repository.findTodaysFactors(USER).stream()
            .filter(f -> f.executableId().equals(taskId))
            .map(ExecutableFactors::urgencyRaw)
            .findFirst()
            .orElseThrow();
    }

    private UUID insertCycle(String type, String status, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, parent_cycle_id, name, type, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, USER, parent, type + "-" + id, type, status);
        return id;
    }

    private UUID insertTask(String name, OffsetDateTime endTime) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, end_time)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, endTime);
        return id;
    }
}
