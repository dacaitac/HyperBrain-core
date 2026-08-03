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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the JDBC read side of the Prioritizer against a real PostgreSQL: the recursive
 * {@code parent_cycle_id} walk that yields the graded-alignment contexts, and the cycle-deadline
 * -anchored raw urgency (ADR-026) — urgency derived from {@code MIN(core_cycle.end_date)} across the
 * executable's whole structural cycle chain, not from {@code executable.end_time}. Black-box: only the
 * public {@link PriorityStateRepository} is exercised; the domain grading itself is unit-tested.
 */
@IntegrationTest
@DisplayName("JdbcPriorityStateRepository — graded alignment CTE + cycle-deadline urgency (ADR-026)")
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
    @DisplayName("ADR-026: a reminder-backed TASK (no end_time) inside a cycle with a near end_date now gets urgency")
    void reminder_backed_task_in_dated_cycle_gets_urgency() {
        // The hole ADR-026 closes: DR-01 strips end_time on reminder-backed rows, so before the
        // re-anchor this task carried urgency 0 forever. Its cycle's end_date now drives urgency.
        UUID cycle = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().plusDays(1));
        UUID task = insertTaskInCycle("Due-soon task", cycle);

        assertThat(urgencyOf(task)).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("ADR-026: urgency is 0 while the cycle deadline is beyond the 7-day horizon")
    void urgency_zero_outside_horizon() {
        UUID cycle = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().plusDays(30));
        UUID task = insertTaskInCycle("Far task", cycle);

        assertThat(urgencyOf(task)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("ADR-026: urgency sits on the ramp for a cycle deadline inside the horizon (0 < u < 5)")
    void urgency_on_the_ramp_inside_horizon() {
        // end_date today + 3 -> deadline instant is the start of day +4, so days_to_deadline is in
        // (3, 4] depending on the wall-clock time; urgency = 5·(1 − dtd/7) lands in (2.14, 2.86].
        UUID cycle = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().plusDays(3));
        UUID task = insertTaskInCycle("Mid-ramp task", cycle);

        double urgency = urgencyOf(task);

        assertThat(urgency).isGreaterThan(2.0).isLessThan(3.0);
    }

    @Test
    @DisplayName("ADR-026: an overdue cycle deadline rises above 5 up to the cap 6")
    void overdue_cycle_deadline_exceeds_five_capped_at_six() {
        UUID cycle = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().minusDays(5));
        UUID task = insertTaskInCycle("Overdue task", cycle);

        double urgency = urgencyOf(task);

        assertThat(urgency).isGreaterThan(5.0).isLessThanOrEqualTo(6.0);
    }

    @Test
    @DisplayName("ADR-026: MIN over the chain wins — an ancestor's earlier deadline drives urgency, NULLs are skipped")
    void min_deadline_over_the_chain_wins() {
        // near ancestor deadline < own far deadline; an intermediate cycle with no end_date is skipped.
        UUID near = insertCycle("MCI", "ACTIVE", null, LocalDate.now().plusDays(1));
        UUID middleNull = insertCycle("OBJECTIVE", "ACTIVE", near, null);
        UUID own = insertCycle("PROJECT", "ACTIVE", middleNull, LocalDate.now().plusDays(60));
        UUID task = insertTaskInCycle("Chained task", own);

        double chained = urgencyOf(task);

        // Same executable pinned only to its own far cycle would score ~0; the ancestor's near
        // deadline lifts it well onto the ramp.
        UUID isolated = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().plusDays(60));
        double isolatedUrgency = urgencyOf(insertTaskInCycle("Isolated task", isolated));

        assertThat(isolatedUrgency).isCloseTo(0.0, within(1e-6));
        assertThat(chained).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("ADR-026: a COMPLETED ancestor's earlier (past) deadline is ineligible — no spurious overdue urgency 6")
    void completed_ancestor_deadline_is_ineligible() {
        // Earliest end_date is on a COMPLETED ancestor in the past; only ACTIVE cycles feed the MIN,
        // so the executable falls to its own ACTIVE far deadline and scores ~0, NOT the overdue 6.
        UUID completedPast = insertCycle("MCI", "COMPLETED", null, LocalDate.now().minusDays(5));
        UUID ownFarActive = insertCycle("PROJECT", "ACTIVE", completedPast, LocalDate.now().plusDays(60));
        UUID task = insertTaskInCycle("Under a finished MCI", ownFarActive);

        assertThat(urgencyOf(task)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("ADR-026: with the earliest deadline COMPLETED, urgency falls to the next ACTIVE cycle with an end_date")
    void falls_to_next_active_cycle_with_deadline() {
        // Chain: COMPLETED past (earliest) -> ACTIVE near -> own ACTIVE undated. The MIN over ACTIVE
        // cycles is the near deadline, so urgency is on the ramp (>0, <6), not the spurious overdue 6.
        UUID completedPast = insertCycle("MCI", "COMPLETED", null, LocalDate.now().minusDays(5));
        UUID activeNear = insertCycle("OBJECTIVE", "ACTIVE", completedPast, LocalDate.now().plusDays(1));
        UUID ownUndated = insertCycle("PROJECT", "ACTIVE", activeNear, null);
        UUID task = insertTaskInCycle("Above a finished MCI", ownUndated);

        assertThat(urgencyOf(task)).isGreaterThan(0.0).isLessThan(6.0);
    }

    @Test
    @DisplayName("ADR-026: when every dated cycle in the chain is COMPLETED, urgency is 0 (no active deadline)")
    void all_dated_cycles_completed_yields_zero() {
        UUID completedParent = insertCycle("MCI", "COMPLETED", null, LocalDate.now().minusDays(2));
        UUID completedOwn = insertCycle("PROJECT", "COMPLETED", completedParent, LocalDate.now().plusDays(3));
        UUID task = insertTaskInCycle("Fully finished chain", completedOwn);

        assertThat(urgencyOf(task)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("ADR-026: two executables sharing a cycle share the same raw urgency (per-cycle granularity)")
    void same_cycle_shares_urgency() {
        UUID cycle = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().plusDays(3));
        UUID a = insertTaskInCycle("A", cycle);
        UUID b = insertTaskInCycle("B", cycle);

        // Read both in a single query so they share one now(): the urgency is a pure function of the
        // shared cycle deadline, so same-cycle executables must land the identical raw value.
        Map<UUID, Double> urgencies = repository.findTodaysFactors(USER).stream()
            .collect(java.util.stream.Collectors.toMap(
                ExecutableFactors::executableId, ExecutableFactors::urgencyRaw));

        assertThat(urgencies.get(a)).isEqualTo(urgencies.get(b));
    }

    @Test
    @DisplayName("ADR-026: default 0 with no cycle_id at all (the existing 'no urgency' zero, no new sentinel)")
    void no_cycle_no_urgency() {
        UUID task = insertTask("No cycle", null);

        assertThat(urgencyOf(task)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("ADR-026: default 0 when the whole cycle chain carries no end_date")
    void chain_without_end_date_has_no_urgency() {
        UUID parent = insertCycle("MCI", "ACTIVE", null, null);
        UUID own = insertCycle("PROJECT", "ACTIVE", parent, null);
        UUID task = insertTaskInCycle("Undated chain", own);

        assertThat(urgencyOf(task)).isCloseTo(0.0, within(1e-6));
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
    @DisplayName("findFactors returns one executable's factors including its cycle-derived urgency; a system-generated row carries no priority")
    void find_factors_single_row() {
        // Overdue cycle deadline -> the single-executable read derives urgency identically to the day
        // query, so an overdue chain caps at 6.
        UUID cycle = insertCycle("PROJECT", "ACTIVE", null, LocalDate.now().minusDays(5));
        UUID task = insertTaskInCycle("One", cycle);

        assertThat(repository.findFactors(task)).hasValueSatisfying(f -> {
            assertThat(f.executableId()).isEqualTo(task);
            assertThat(f.urgencyRaw()).isGreaterThan(5.0).isLessThanOrEqualTo(6.0);
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

    @Test
    @DisplayName("ADR-036: an AREA cycle is out of the chain — it resolves with own_type AREA and injects no alignment or urgency")
    void area_cycle_is_out_of_the_alignment_chain() {
        // An AREA is a perpetual classification (no end_date) linked only by core_cycle_area, never by
        // parent_cycle_id. The CTEs still see it as a source cycle, so valueOf('AREA') must resolve and
        // it must contribute no signal: own band is 0.0 and, being perpetual, it lends no deadline.
        UUID area = insertCycle("AREA", "ACTIVE", null, null);
        UUID task = insertTaskInCycle("Task pinned straight to an AREA", area);

        CycleAlignmentContext context = repository.findAlignmentContexts(USER).get(area);
        assertThat(context.ownType()).isEqualTo(CycleType.AREA);
        assertThat(urgencyOf(task)).isCloseTo(0.0, within(1e-6));
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
        return insertCycle(type, status, parent, null);
    }

    private UUID insertCycle(String type, String status, UUID parent, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, parent_cycle_id, name, type, status, end_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, USER, parent, type + "-" + id, type, status, endDate);
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

    private UUID insertTaskInCycle(String name, UUID cycleId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status)
            VALUES (?, ?, ?, ?, 'TASK', 'TODO')
            """, id, USER, cycleId, name);
        return id;
    }
}
