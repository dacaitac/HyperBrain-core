package com.hyperbrain.planner;

import com.hyperbrain.planner.application.DailyAdherenceRollupService;
import com.hyperbrain.planner.domain.model.DailyAdherenceReport;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the H0 daily rollup (#17) against a real PostgreSQL: the {@link DailyAdherenceRollupService}
 * reads a settled local day and derives adherence and the behavioral lead measures from the concrete
 * {@code core_time_block} execution signal. Black-box: only the application service is exercised; the
 * formula itself is unit-tested. The user is pinned to UTC so the fixtures' UTC instants and the
 * timezone-projected day boundaries agree.
 */
@IntegrationTest
@DisplayName("DailyAdherenceRollupService — H0 rollup over a real day (#17)")
class DailyAdherenceRollupServiceIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 20);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DailyAdherenceRollupService service;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM plnr_daily_rollup");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("UPDATE sys_user SET settings = '{}'::jsonb WHERE id = ?", USER);
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    @Test
    @DisplayName("adherence is the executed fraction of the day's planner blocks")
    void computes_adherence_from_executed_blocks() {
        UUID task = insertTask("Deep work");
        insertPlannerBlock(task, 9, 60, "SETTLED");   // executed 60 min
        insertPlannerBlock(task, 11, 45, "SETTLED");  // executed 45 min
        insertPlannerBlock(task, 14, null, "EXPIRED"); // never executed

        DailyAdherenceReport report = service.rollup(USER, DAY);

        assertThat(report.date()).isEqualTo(DAY);
        assertThat(report.blocksPlanned()).isEqualTo(3);
        assertThat(report.blocksExecuted()).isEqualTo(2);
        assertThat(report.adherence()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(report.abandoned()).isFalse();
    }

    @Test
    @DisplayName("wig_hit is true when the reserved WIG block was executed, false otherwise")
    void wig_hit_tracks_the_reserved_lead_measure_block() {
        UUID wig = insertLeadMeasure("Ship MVP");
        insertPlannerBlock(wig, 8, 50, "SETTLED");

        assertThat(service.rollup(USER, DAY).wigHit()).isTrue();

        jdbcTemplate.update("UPDATE core_time_block SET actual_duration_minutes = NULL, status = 'EXPIRED'");

        assertThat(service.rollup(USER, DAY).wigHit()).isFalse();
    }

    @Test
    @DisplayName("a low-adherence day is abandoned without a replan, re-adjusted with one")
    void abandoned_versus_replan_day() {
        UUID task = insertTask("Neglected");
        insertPlannerBlock(task, 9, 30, "SETTLED");    // 1 executed
        insertPlannerBlock(task, 11, null, "EXPIRED"); // 3 dropped
        insertPlannerBlock(task, 13, null, "EXPIRED");
        insertPlannerBlock(task, 15, null, "EXPIRED");

        DailyAdherenceReport abandoned = service.rollup(USER, DAY);
        assertThat(abandoned.adherence()).isCloseTo(0.25, within(1e-9));
        assertThat(abandoned.replanCount()).isZero();
        assertThat(abandoned.abandoned()).isTrue();

        insertReplanCommand(OffsetDateTime.of(2026, 7, 20, 12, 0, 0, 0, UTC));

        DailyAdherenceReport readjusted = service.rollup(USER, DAY);
        assertThat(readjusted.replanCount()).isEqualTo(1);
        assertThat(readjusted.abandoned()).isFalse();
    }

    @Test
    @DisplayName("an empty day yields zero adherence and is not abandoned")
    void empty_day_is_zero() {
        DailyAdherenceReport report = service.rollup(USER, DAY);

        assertThat(report.blocksPlanned()).isZero();
        assertThat(report.adherence()).isZero();
        assertThat(report.wigHit()).isFalse();
        assertThat(report.abandoned()).isFalse();
    }

    @Test
    @DisplayName("persists the rollup to plnr_daily_rollup mirroring the computed report")
    void persists_the_projection_to_plnr_daily_rollup() {
        UUID wig = insertLeadMeasure("Ship MVP");
        insertPlannerBlock(wig, 8, 50, "SETTLED");    // WIG executed
        UUID task = insertTask("Deep work");
        insertPlannerBlock(task, 11, null, "EXPIRED"); // dropped

        DailyAdherenceReport report = service.rollup(USER, DAY);

        assertThat(rollupRowCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT * FROM plnr_daily_rollup WHERE user_id = ? AND agenda_date = ?",
            USER, Date.valueOf(DAY));
        assertThat(row)
            .containsEntry("blocks_planned", report.blocksPlanned())
            .containsEntry("blocks_executed", report.blocksExecuted())
            .containsEntry("adherence", report.adherence())
            .containsEntry("wig_hit", report.wigHit())
            .containsEntry("ritual_completed", report.ritualCompleted())
            .containsEntry("replan_count", report.replanCount())
            .containsEntry("abandoned", report.abandoned());
        assertThat(row.get("materialized_at")).isNotNull();
    }

    @Test
    @DisplayName("re-rolling the same day upserts the row instead of duplicating or failing on the PK")
    void re_running_the_same_day_upserts() {
        UUID task = insertTask("Deep work");
        insertPlannerBlock(task, 9, 60, "SETTLED");    // executed
        insertPlannerBlock(task, 11, null, "EXPIRED"); // dropped

        DailyAdherenceReport first = service.rollup(USER, DAY);
        assertThat(rollupRowCount()).isEqualTo(1);
        assertThat(first.blocksExecuted()).isEqualTo(1);

        // The day settles further: the previously-expired block is now executed.
        jdbcTemplate.update(
            "UPDATE core_time_block SET actual_duration_minutes = 30, status = 'SETTLED' "
                + "WHERE actual_duration_minutes IS NULL");

        DailyAdherenceReport second = service.rollup(USER, DAY);

        assertThat(second.blocksExecuted()).isGreaterThan(first.blocksExecuted());
        assertThat(rollupRowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT blocks_executed FROM plnr_daily_rollup WHERE user_id = ? AND agenda_date = ?",
            Integer.class, USER, Date.valueOf(DAY)))
            .isEqualTo(second.blocksExecuted());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT adherence FROM plnr_daily_rollup WHERE user_id = ? AND agenda_date = ?",
            Double.class, USER, Date.valueOf(DAY)))
            .isEqualTo(second.adherence());
    }

    private int rollupRowCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM plnr_daily_rollup WHERE user_id = ? AND agenda_date = ?",
            Integer.class, USER, Date.valueOf(DAY));
        return count == null ? 0 : count;
    }

    private UUID insertTask(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', 0.5)
            """, id, USER, name);
        return id;
    }

    private UUID insertLeadMeasure(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'LEAD_MEASURE', 'TODO', 0.9)
            """, id, USER, name);
        return id;
    }

    private void insertPlannerBlock(UUID executableId, int startHour, Integer actualMinutes, String status) {
        OffsetDateTime start = OffsetDateTime.of(2026, 7, 20, startHour, 0, 0, 0, UTC);
        jdbcTemplate.update("""
            INSERT INTO core_time_block
                (id, executable_id, date_start, date_end, status, origin, planned_minutes,
                 actual_duration_minutes, settled_at)
            VALUES (?, ?, ?, ?, ?, 'PLANNER', 60, ?, ?)
            """,
            UUID.randomUUID(), executableId, start, start.plusHours(1), status,
            actualMinutes, start.plusHours(1));
    }

    private void insertReplanCommand(OffsetDateTime processedAt) {
        jdbcTemplate.update("""
            INSERT INTO processed_message (message_id, event_type, processed_at)
            VALUES (?, 'REPLAN_AGENDA', ?)
            """, "user-command:" + UUID.randomUUID(), processedAt);
    }
}
