package com.hyperbrain.planner;

import com.hyperbrain.planner.application.AgendaGenerationService;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the deterministic agenda-generation pipeline (#6a) against a real PostgreSQL: the
 * {@link AgendaGenerationService} reads the day's aggregates, materializes {@code PLANNED} blocks,
 * re-imposes the hard walls, and persists the result. Black-box: only the public application service
 * is exercised; the domain rules themselves are unit-tested.
 */
@IntegrationTest
@DisplayName("AgendaGenerationService — deterministic floor pipeline (#6a)")
class AgendaGenerationServiceIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, UTC);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgendaGenerationService service;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        jdbcTemplate.update("UPDATE sys_user SET settings = '{}'::jsonb WHERE id = ?", USER);
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        // Pin the user to UTC so the fixtures' UTC instants and the frontier's local projection agree.
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    @Test
    @DisplayName("materializes PLANNED blocks for ranked executables and persists them")
    void materializes_and_persists_blocks() {
        UUID high = insertTask("High", 0.9, 60);
        UUID low = insertTask("Low", 0.3, 60);

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        assertThat(agenda.blocks()).hasSize(2);
        List<Map<String, Object>> persisted = jdbcTemplate.queryForList(
            "SELECT executable_id, status, origin FROM core_time_block ORDER BY date_start");
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).get("executable_id")).isEqualTo(high);
        assertThat(persisted).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PLANNED");
            assertThat(row.get("origin")).isEqualTo("PLANNER");
        });
        assertThat(persisted.get(1).get("executable_id")).isEqualTo(low);
    }

    @Test
    @DisplayName("wall: a read-only AGENDA executable window is never scheduled over")
    void does_not_schedule_over_agenda_wall() {
        // AGENDA occupies 07:00-09:00 (within the default cold-start wake window).
        insertAgendaEvent("Meeting",
            OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        UUID task = insertTask("Work", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block WHERE executable_id = ?",
            OffsetDateTime.class, task);
        // The block cannot start before the AGENDA wall ends at 09:00.
        assertThat(blockStart).isAfterOrEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        // And the AGENDA executable itself is never given a block.
        Integer agendaBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block b JOIN core_executable e ON e.id = b.executable_id "
                + "WHERE e.type = 'AGENDA'", Integer.class);
        assertThat(agendaBlocks).isZero();
    }

    @Test
    @DisplayName("F1: the WIG lead measure of the active MCI is reserved first")
    void reserves_wig_first() {
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID wig = insertLeadMeasure("Predictive action", mci, 0.5);
        insertTask("Whirlwind", 0.99, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        // The earliest block belongs to the WIG lead measure, ahead of the higher-scored task.
        UUID firstBlockExecutable = jdbcTemplate.queryForObject(
            "SELECT executable_id FROM core_time_block ORDER BY date_start LIMIT 1", UUID.class);
        assertThat(firstBlockExecutable).isEqualTo(wig);
    }

    @Test
    @DisplayName("F1: every active MCI with a lead measure gets a reserved block (portfolio, not one WIG)")
    void reserves_a_block_per_active_mci() {
        UUID mciA = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID mciB = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID wigA = insertLeadMeasure("Lead A", mciA, 0.5);
        UUID wigB = insertLeadMeasure("Lead B", mciB, 0.4);

        service.generate(USER, DAY, UTC, NOON, false);

        List<UUID> wigBlocks = jdbcTemplate.queryForList(
            "SELECT executable_id FROM core_time_block WHERE executable_id IN (?, ?)",
            UUID.class, wigA, wigB);
        assertThat(wigBlocks).containsExactlyInAnyOrder(wigA, wigB);
    }

    @Test
    @DisplayName("F1: an active MCI with no lead measure yields no block (surfaced as an alert, never NaN)")
    void active_mci_without_lead_measure_is_not_reserved() {
        insertCycle("MCI", "ACTIVE", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID task = insertTask("Whirlwind", 0.9, 60);

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        // The MCI carries no lead measure, so no WIG block is reserved; only the ranked task lands.
        assertThat(agenda.blocks()).hasSize(1);
        assertThat(agenda.blocks().get(0).executableId()).isEqualTo(task);
        assertThat(agenda.blocks()).noneMatch(b -> b.wig());
    }

    @Test
    @DisplayName("F1: the WIG portfolio spans the MCI subtree and tolerates progress + prior-block history")
    void wig_portfolio_reads_subtree_and_history() {
        // MCI with a child cycle: a root executable in the child (60% progress) contributes to the
        // MCI's aggregated progress, and a lead measure lives in the subtree.
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID child = insertChildCycle("PROJECT", "ACTIVE", mci);
        UUID wig = insertLeadMeasure("Lead in subtree", child, 0.5);
        insertRootExecutableWithProgress(child, 0.6, 120);
        // A prior-day planner block for the lead measure exercises the history columns.
        insertPlannerBlock(wig, NOON.minusDays(1));

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        // The subtree lead measure is reserved as the WIG despite living under a descendant cycle.
        assertThat(agenda.blocks()).filteredOn(b -> b.wig()).extracting(b -> b.executableId())
            .containsExactly(wig);
    }

    @Test
    @DisplayName("observed sleep frontier: enough fresh records shift the window off the cold-start default")
    void observed_frontier_replaces_cold_start() {
        // Five fresh nights waking at 05:00 UTC, well before the 06:30 cold-start default.
        for (int i = 1; i <= 5; i++) {
            insertSleepRecord(
                OffsetDateTime.of(2026, 7, 10, 0, 0, 0, 0, UTC).minusDays(i),   // bedtime ~00:00
                OffsetDateTime.of(2026, 7, 10, 5, 0, 0, 0, UTC).minusDays(i),   // wake 05:00
                80, OffsetDateTime.now(UTC).minusHours(2));
        }
        UUID task = insertTask("Early bird", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block WHERE executable_id = ?",
            OffsetDateTime.class, task);
        // Wake observed at 05:00 lets the block start earlier than the 06:30 cold-start default would.
        assertThat(blockStart).isEqualTo(OffsetDateTime.of(2026, 7, 10, 5, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("replan-from-now: no block is scheduled before the reference instant")
    void replan_respects_now() {
        insertTask("Afternoon work", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, true);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM core_time_block", OffsetDateTime.class);
        assertThat(blockStart).isAfterOrEqualTo(NOON);
    }

    // ─── fixtures ──────────────────────────────────────────────────────────────

    private UUID insertTask(String name, double priority, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
    }

    private UUID insertLeadMeasure(String name, UUID cycleId, double priority) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status, priority_score)
            VALUES (?, ?, ?, ?, 'LEAD_MEASURE', 'TODO', ?)
            """, id, USER, cycleId, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, 30)
            """, id);
        return id;
    }

    private void insertAgendaEvent(String name, OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, 'AGENDA', 'TODO', ?, ?)
            """, id, USER, name, start, end);
    }

    private UUID insertCycle(String type, String status, LocalDate startDate, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, start_date, end_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, USER, type + "-cycle", type, status, startDate, endDate);
        return id;
    }

    private UUID insertChildCycle(String type, String status, UUID parentCycleId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, parent_cycle_id, name, type, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, USER, parentCycleId, type + "-child", type, status);
        return id;
    }

    private UUID insertRootExecutableWithProgress(UUID cycleId, double progress, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status, progress)
            VALUES (?, ?, ?, 'Progressing root', 'TASK', 'IN_PROGRESS', ?)
            """, id, USER, cycleId, progress);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
    }

    private void insertPlannerBlock(UUID executableId, OffsetDateTime start) {
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin)
            VALUES (?, ?, ?, ?, 'SETTLED', 'PLANNER')
            """, UUID.randomUUID(), executableId, start, start.plusMinutes(45));
    }

    private void insertSleepRecord(OffsetDateTime bedtime, OffsetDateTime wake, Integer sleepScore,
                                   OffsetDateTime collectedAt) {
        jdbcTemplate.update("""
            INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, collected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), USER, bedtime, wake, sleepScore, collectedAt);
    }
}
