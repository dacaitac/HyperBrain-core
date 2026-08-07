package com.hyperbrain.core;

import com.hyperbrain.core.application.OverdueSweepService;
import com.hyperbrain.core.application.TimeBlockSettlementService;
import com.hyperbrain.core.domain.model.OverdueSweepReport;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the ADR-040 D4 day-close sweep against a real PostgreSQL: the seven
 * behaviours of the dispatch table, the idempotence of a second run, the detach-before-re-date
 * order for a contained task, the AGENDA exemption from recurrence cloning, and the fact that the
 * sweep only ever notifies the satellites — it never deletes.
 */
@IntegrationTest
@DisplayName("Day-close sweep of what expired, by type (ADR-040 D4)")
class OverdueSweepIT {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    /** The sweep opens this day and closes the one before it. */
    private static final LocalDate REFERENCE_DAY = LocalDate.of(2026, 8, 7);
    /** 2026-08-06 14:30 in Bogota (UTC-5) — squarely inside the day that just closed. */
    private static final OffsetDateTime YESTERDAY_AFTERNOON =
        LocalDate.of(2026, 8, 6).atTime(14, 30).atZone(ZONE).toOffsetDateTime();
    /** 2026-08-07 09:00 in Bogota — the reference day itself, so it has not expired. */
    private static final OffsetDateTime TODAY_MORNING =
        REFERENCE_DAY.atTime(9, 0).atZone(ZONE).toOffsetDateTime();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OverdueSweepService sweepService;
    @Autowired private TimeBlockSettlementService settlementService;

    private UUID userId;
    private UUID cycleId;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET container_block_id = NULL, "
            + "imputed_block_id = NULL, imputed_time_block_id = NULL, parent_id = NULL");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            userId = DataFixture.insertSystemUser(conn);
        }
        cycleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO core_cycle (id, user_id, name, type, status) VALUES (?, ?, 'C', 'PROJECT', 'ACTIVE')",
            cycleId, userId);
    }

    @Test
    @DisplayName("each type closes its day its own way, and only the expired ones are touched")
    void dispatches_every_type_of_the_table() {
        UUID habit = insert("Habit", "HABIT", "TODO", YESTERDAY_AFTERNOON, null, null);
        UUID lead = insert("Lead", "LEAD_MEASURE", "TODO", YESTERDAY_AFTERNOON, null, null);
        UUID activity = insert("Activity", "ACTIVITY", "TODO",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(1), null);
        UUID agenda = insert("Agenda", "AGENDA", "TODO",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusMinutes(20), null);
        UUID task = insert("Task", "TASK", "TODO", YESTERDAY_AFTERNOON, null, null);
        UUID buying = insert("Milk", "BUYING", "TODO", YESTERDAY_AFTERNOON, null, null);
        // Out of scope by predicate, each for its own reason.
        UUID today = insert("Today", "TASK", "TODO", TODAY_MORNING, null, null);
        UUID dateless = insert("Bag", "TASK", "TODO", null, null, null);
        UUID learning = insert("Study", "LEARNING_SESSION", "TODO",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(1), null);
        UUID block = insert("Block", "TIME_BLOCK", "PLANNED",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(2), null);

        OverdueSweepReport report = sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(report.closedAsFailed()).isEqualTo(4);
        assertThat(report.rescheduled()).isEqualTo(1);
        assertThat(report.dateCleared()).isEqualTo(1);
        assertThat(status(habit)).isEqualTo("FAILED");
        assertThat(status(lead)).isEqualTo("FAILED");
        assertThat(status(activity)).isEqualTo("FAILED");
        assertThat(status(agenda)).isEqualTo("FAILED");
        // The task is the only type that moves instead of closing: same row, new day, same hour.
        assertThat(status(task)).isEqualTo("TODO");
        assertThat(localDate(startTime(task))).isEqualTo(REFERENCE_DAY);
        assertThat(startTime(task).atZoneSameInstant(ZONE).toLocalTime())
            .isEqualTo(YESTERDAY_AFTERNOON.atZoneSameInstant(ZONE).toLocalTime());
        // The purchase goes back to the dateless bag.
        assertThat(status(buying)).isEqualTo("TODO");
        assertThat(startTime(buying)).isNull();
        // Untouched.
        assertThat(startTime(today)).isEqualTo(TODAY_MORNING);
        assertThat(startTime(dateless)).isNull();
        assertThat(status(learning)).isEqualTo("TODO");
        assertThat(status(block)).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("running the sweep twice moves nothing and emits nothing the second time")
    void second_run_is_a_noop() {
        insert("Habit", "HABIT", "TODO", YESTERDAY_AFTERNOON, null, null);
        UUID task = insert("Task", "TASK", "TODO", YESTERDAY_AFTERNOON, null, null);
        insert("Milk", "BUYING", "TODO", YESTERDAY_AFTERNOON, null, null);

        sweepService.sweep(userId, REFERENCE_DAY, ZONE);
        OffsetDateTime afterFirstRun = startTime(task);
        int eventsAfterFirstRun = outboxCount();

        OverdueSweepReport second = sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(second.isEmpty()).isTrue();
        assertThat(startTime(task)).isEqualTo(afterFirstRun);
        assertThat(outboxCount()).isEqualTo(eventsAfterFirstRun);
    }

    @Test
    @DisplayName("a task that sat in yesterday's block is detached and lands on the new day's midnight placeholder")
    void contained_task_is_detached_and_normalized_to_midnight() {
        UUID block = insert("Block", "TIME_BLOCK", "PLANNED",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(2), null);
        UUID task = insert("Contained", "TASK", "TODO", YESTERDAY_AFTERNOON, null, block);

        sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(containerOf(task)).isNull();
        assertThat(startTime(task))
            .isEqualTo(REFERENCE_DAY.atStartOfDay(ZONE).toOffsetDateTime());
        // The block itself is untouched by this sweep — its own settlement path owns it.
        assertThat(status(block)).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("a purchase left inside yesterday's block is detached too, so the hard copy cannot stamp the date back")
    void contained_buying_is_detached_before_losing_its_date() {
        UUID block = insert("Block", "TIME_BLOCK", "PLANNED",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(2), null);
        UUID buying = insert("Milk", "BUYING", "TODO", YESTERDAY_AFTERNOON, null, block);

        sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(containerOf(buying)).isNull();
        assertThat(startTime(buying)).isNull();
    }

    @Test
    @DisplayName("the re-dated window is hard-copied down to the subtasks, which are never swept on their own")
    void re_dating_carries_the_subtasks() {
        UUID task = insert("Parent", "TASK", "TODO", YESTERDAY_AFTERNOON, null, null);
        UUID subtask = insertChild("Subtask", task, YESTERDAY_AFTERNOON);

        OverdueSweepReport report = sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        // Only the parent was a candidate; the subtask moved because its date is a hard copy.
        assertThat(report.rescheduled()).isEqualTo(1);
        assertThat(startTime(subtask)).isEqualTo(startTime(task));
        assertThat(localDate(startTime(subtask))).isEqualTo(REFERENCE_DAY);
        assertThat(updateEventsFor(subtask)).isEqualTo(1);
    }

    @Test
    @DisplayName("an AGENDA with a frequency fails WITHOUT cloning, while a HABIT with the same frequency schedules its next occurrence")
    void agenda_is_the_only_type_exempt_from_recurrence_cloning() {
        UUID agenda = insertRecurring("Standup", "AGENDA", YESTERDAY_AFTERNOON, 1.0);
        UUID habit = insertRecurring("Gym", "HABIT", YESTERDAY_AFTERNOON, 1.0);

        sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(status(agenda)).isEqualTo("FAILED");
        assertThat(status(habit)).isEqualTo("FAILED");
        assertThat(clonesOf("Standup")).isEmpty();
        List<OffsetDateTime> habitClones = clonesOf("Gym");
        assertThat(habitClones).hasSize(1);
        assertThat(habitClones.get(0)).isEqualTo(YESTERDAY_AFTERNOON.plusDays(1));
    }

    @Test
    @DisplayName("closing as a sanctioned miss resets the streak, stamps the completion clock and notifies both satellites — deleting nothing")
    void closure_resets_the_streak_and_only_notifies() {
        UUID habit = insert("Habit", "HABIT", "TODO", YESTERDAY_AFTERNOON, null, null);
        jdbcTemplate.update(
            "UPDATE core_executable SET current_streak = 5, best_streak = 5 WHERE id = ?", habit);

        sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(intColumn(habit, "current_streak")).isZero();
        assertThat(intColumn(habit, "best_streak")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT last_completed_at IS NOT NULL FROM core_executable WHERE id = ?",
            Boolean.class, habit)).isTrue();
        assertThat(updateEventsFor(habit)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableDeletedEvent'",
            Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE source_system <> 'SYSTEM'",
            Integer.class)).isZero();
    }

    @Test
    @DisplayName("an item closed by hand before the sweep is left alone: no second closure, no clone, no event")
    void already_closed_rows_are_not_re_closed() {
        UUID habit = insertRecurring("Gym", "HABIT", YESTERDAY_AFTERNOON, 1.0);
        jdbcTemplate.update("UPDATE core_executable SET status = 'DONE' WHERE id = ?", habit);

        OverdueSweepReport report = sweepService.sweep(userId, REFERENCE_DAY, ZONE);

        assertThat(report.isEmpty()).isTrue();
        assertThat(status(habit)).isEqualTo("DONE");
        assertThat(clonesOf("Gym")).isEmpty();
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("ADR-040 D4: the block expiry sweep now settles an elapsed block as DONE, not FAILED")
    void elapsed_block_settles_as_done() {
        UUID block = insert("Block", "TIME_BLOCK", "PLANNED",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(2), null);

        int settled = settlementService.expireDueBlocks(YESTERDAY_AFTERNOON.plusHours(3));

        assertThat(settled).isEqualTo(1);
        assertThat(status(block)).isEqualTo("DONE");
        // A container of time that elapsed is not an achievement: no streak was extended.
        assertThat(intColumn(block, "current_streak")).isZero();
        assertThat(intColumn(block, "best_streak")).isZero();
        // The settlement notifies the mirrors, so the block stops reading "not started" forever.
        assertThat(updateEventsFor(block)).isEqualTo(1);
    }

    @Test
    @DisplayName("settling the same block twice notifies the satellites once")
    void settling_twice_notifies_once() {
        UUID block = insert("Block", "TIME_BLOCK", "PLANNED",
            YESTERDAY_AFTERNOON, YESTERDAY_AFTERNOON.plusHours(2), null);
        OffsetDateTime after = YESTERDAY_AFTERNOON.plusHours(3);

        settlementService.expireDueBlocks(after);
        settlementService.expireDueBlocks(after);

        assertThat(updateEventsFor(block)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events WHERE event_type = 'TimeBlockSettledEvent'
            """, Integer.class)).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insert(String name, String type, String status, OffsetDateTime start,
                        OffsetDateTime end, UUID containerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, cycle_id, name, type, status, start_time, end_time,
                 container_block_id, origin, system_generated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
            """, id, userId, cycleId, name, type, status, start, end, containerId,
            "TIME_BLOCK".equals(type) ? "PLANNER" : null);
        return id;
    }

    private UUID insertRecurring(String name, String type, OffsetDateTime start, double frequency) {
        UUID id = insert(name, type, "TODO", start, null, null);
        jdbcTemplate.update("UPDATE core_executable SET frequency = ? WHERE id = ?", frequency, id);
        return id;
    }

    private UUID insertChild(String name, UUID parentId, OffsetDateTime start) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, parent_id, name, type, status, start_time, system_generated)
            VALUES (?, ?, ?, ?, 'TASK', 'TODO', ?, false)
            """, id, userId, parentId, name, start);
        return id;
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM core_executable WHERE id = ?", String.class, id);
    }

    private OffsetDateTime startTime(UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, id);
    }

    private UUID containerOf(UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, id);
    }

    private int intColumn(UUID id, String column) {
        return jdbcTemplate.queryForObject(
            "SELECT " + column + " FROM core_executable WHERE id = ?", Integer.class, id);
    }

    /** The clones DR-04 created for a given name, by their scheduled occurrence. */
    private List<OffsetDateTime> clonesOf(String name) {
        return jdbcTemplate.queryForList("""
            SELECT start_time FROM core_executable
            WHERE name = ? AND status = 'TODO'
            ORDER BY start_time
            """, OffsetDateTime.class, name);
    }

    private int updateEventsFor(UUID id) {
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_id = ? AND event_type = 'ExecutableUpdatedEvent'
            """, Integer.class, id.toString());
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
    }

    private static LocalDate localDate(OffsetDateTime instant) {
        return instant.atZoneSameInstant(ZONE).toLocalDate();
    }
}
