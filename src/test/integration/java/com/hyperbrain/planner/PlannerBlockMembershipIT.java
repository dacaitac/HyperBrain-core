package com.hyperbrain.planner;

import com.hyperbrain.planner.application.PlannerBlockMaterializer;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the window model's persistence contract against a real PostgreSQL (ADR-040 D7/D10/D11/D17):
 * a block is a {@code TIME_BLOCK} executable holding its members through {@code container_block_id}, it
 * is recognised across regenerations by its template slot, its withdrawal lets every member go
 * <b>before</b> the row is deleted, and a plan that did not move writes nothing at all.
 *
 * <p>This replaces the bridge-table contract of ADR-027: {@code core_time_block_member} is frozen
 * history, containment is monovalent and the block carries no anchor row any more.
 */
@IntegrationTest
@DisplayName("Window model persistence — slot identity, containment and withdrawal (ADR-040)")
class PlannerBlockMembershipIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final String GOAL_SLOT = "GOAL_MORNING";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlannerStateRepository repository;
    @Autowired private PlannerBlockMaterializer materializer;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    @Test
    @DisplayName("a window is persisted as a TIME_BLOCK executable holding its members in order")
    void a_window_is_a_block_executable_holding_its_members() {
        UUID first = insertTask("Write the report");
        UUID second = insertTask("Review PRs");

        materialize(List.of(window(first, List.of(second), 9, 11)));

        UUID blockId = blockId();
        assertThat(jdbcTemplate.queryForMap(
            "SELECT type, status, origin, template_slot_id FROM core_executable WHERE id = ?", blockId))
            .containsEntry("type", "TIME_BLOCK")
            .containsEntry("status", "PLANNED")
            .containsEntry("origin", "PLANNER")
            .containsEntry("template_slot_id", GOAL_SLOT);
        assertThat(containmentOf(first)).isEqualTo(blockId);
        assertThat(containmentOf(second)).isEqualTo(blockId);
        assertThat(ordOf(first)).isZero();
        assertThat(ordOf(second)).isEqualTo(1);
        // The container's two hours are split across its members, so the quotas always add up.
        assertThat(plannedMinutesOf(first) + plannedMinutesOf(second)).isEqualTo(120);
    }

    @Test
    @DisplayName("the hard copy reaches every member: containing a task stamps the window's date on it")
    void containment_hard_copies_the_window_date_onto_its_members() {
        UUID task = insertTask("Write the report");

        materialize(List.of(window(task, List.of(), 9, 11)));

        // DR-10 through core's published operation: the member carries the container's start, and no
        // end instant (DR-01 — a TASK is reminder-backed).
        assertThat(startTimeOf(task)).isEqualTo(at(9));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT end_time FROM core_executable WHERE id = ?", OffsetDateTime.class, task)).isNull();
    }

    @Test
    @DisplayName("the block is recognised by its slot, so renaming it never mints a new id (D7)")
    void the_slot_carries_the_identity_across_a_rename() {
        UUID task = insertTask("Write the report");
        materialize(List.of(window(task, List.of(), 9, 11)));
        UUID original = blockId();
        // The LLM renames the block — exactly the case that would have duplicated its calendar event
        // if identity had ever depended on the name.
        jdbcTemplate.update("UPDATE core_executable SET name = 'Deep work morning' WHERE id = ?", original);

        // A later replan moves the same slot to another hour and offers a different membership.
        UUID other = insertTask("Something else");
        materialize(List.of(window(other, List.of(), 14, 16)));

        assertThat(blockId()).isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM core_executable WHERE id = ?", String.class, original))
            .isEqualTo("Deep work morning");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, original))
            .isEqualTo(at(14));
    }

    @Test
    @DisplayName("re-materializing an unchanged plan writes nothing at all (D17)")
    void an_unchanged_plan_is_silent() {
        UUID task = insertTask("Write the report");
        materialize(List.of(window(task, List.of(), 9, 11)));
        jdbcTemplate.update("DELETE FROM outbox_events");

        materialize(List.of(window(task, List.of(), 9, 11)));

        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("withdrawing a block lets its members go, with their event, before deleting the row (D10)")
    void withdrawal_releases_members_before_deleting() {
        UUID task = insertTask("Write the report");
        materialize(List.of(window(task, List.of(), 9, 11)));
        UUID blockId = blockId();
        jdbcTemplate.update("DELETE FROM outbox_events");

        // The next plan wants nothing on this day.
        List<UUID> withdrawn = materialize(List.of());

        assertThat(withdrawn).containsExactly(blockId);
        assertThat(blockCount()).isZero();
        // The member survived, detached, and its hour fell back to the midnight placeholder: the day
        // survives, the hour dies (the hour belonged to the block, not to the user).
        assertThat(containmentOf(task)).isNull();
        assertThat(startTimeOf(task)).isEqualTo(DAY.atStartOfDay(ZONE).toOffsetDateTime());
        // Both the release and the deletion were announced — the mirrors are never left holding the
        // hour of a block that no longer exists.
        assertThat(eventTypes()).contains("ExecutableUpdatedEvent", "ExecutableDeletedEvent");
    }

    @Test
    @DisplayName("a block that earned a history is held back by the delete guard, not deleted")
    void a_block_with_history_survives_the_withdrawal() {
        UUID task = insertTask("Write the report");
        materialize(List.of(window(task, List.of(), 9, 11)));
        UUID blockId = blockId();
        // The block ran: it froze real executed minutes.
        jdbcTemplate.update(
            "UPDATE core_executable SET actual_duration_minutes = 45 WHERE id = ?", blockId);

        List<UUID> withdrawn = materialize(List.of());

        // The guard lives in the delete predicate, so there is no window between checking and deleting.
        assertThat(withdrawn).isEmpty();
        assertThat(blockCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a block that already started is neither re-timed nor withdrawn: the past is not rewritten")
    void a_block_that_already_started_is_frozen() {
        UUID task = insertTask("Write the report");
        materialize(List.of(window(task, List.of(), 9, 11)));
        UUID morning = blockId();

        // A replan fired at 12:00 wants nothing at all. The morning block began at 09:00.
        List<UUID> withdrawn = transactionTemplate.execute(status ->
            materializer.materialize(USER, DAY, ZONE, List.of(), at(12), "criterion"));

        // It stands, untouched, and keeps holding its member: a morning already lived is not a draft.
        assertThat(withdrawn).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, morning))
            .isEqualTo(at(9));
        assertThat(containmentOf(task)).isEqualTo(morning);
    }

    @Test
    @DisplayName("the delivery read answers for the planner's windows only, and that filter is deliberate")
    void the_delivery_read_answers_only_for_the_planners_windows() {
        UUID task = insertTask("Write the report");
        materialize(List.of(window(task, List.of(), 9, 11)));
        UUID plannerBlock = blockId();

        // The three windows whose authorship is not the planner's: one it handed over when Daniel
        // arranged it by hand (still PLANNED — only the origin differs from the block above), one he
        // created himself in Notion (no origin at all, TODO as the ordinary ingestion leaves it) and a
        // focus row. Each holds a member, so each would be delivered if the filter ever widened.
        insertHeldBlock("USER", "PLANNED", 14, insertTask("Facturas"));
        insertHeldBlock(null, "TODO", 16, insertTask("Hyperbrain"));
        insertHeldBlock("FOCUS", "IN_PROGRESS", 18, insertTask("Repasar el informe"));

        // Authorship is the right question *here*, unlike in the goal signals, where a window is a
        // window whoever laid it: this read asks which of the day's windows are the planner's to
        // deliver. One that is already his reaches his satellites through the ordinary executable
        // mirror — since ADR-039 a TIME_BLOCK is an executable and routes to the calendar on its own —
        // so delivering it again would hand him back his own arrangement.
        assertThat(plannedBlocks())
            .extracting(PlannedBlockRecord::blockId)
            .containsExactly(plannerBlock);
    }

    @Test
    @DisplayName("an activity is never contained: it already owns a calendar window of its own")
    void an_activity_is_refused_containment() {
        UUID activity = insertActivity("Gym");
        UUID task = insertTask("Write the report");

        materialize(List.of(window(task, List.of(activity), 9, 11)));

        assertThat(containmentOf(task)).isEqualTo(blockId());
        assertThat(containmentOf(activity)).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<UUID> materialize(List<AgendaBlock> blocks) {
        return transactionTemplate.execute(status ->
            materializer.materialize(
                USER, DAY, ZONE, blocks, DAY.atStartOfDay(ZONE).toOffsetDateTime(), "criterion"));
    }

    private static AgendaBlock window(UUID anchor, List<UUID> additionalMembers, int startHour,
                                      int endHour) {
        return new AgendaBlock(anchor, at(startHour), at(endHour), false, false,
            "Laid against the goal window", additionalMembers, null, GOAL_SLOT);
    }

    private static OffsetDateTime at(int hour) {
        return OffsetDateTime.of(2026, 7, 10, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    private UUID blockId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM core_executable WHERE type = 'TIME_BLOCK' AND origin = 'PLANNER'",
            UUID.class);
    }

    private int blockCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE type = 'TIME_BLOCK'", Integer.class);
        return count == null ? 0 : count;
    }

    private UUID containmentOf(UUID executableId) {
        return jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, executableId);
    }

    private int ordOf(UUID executableId) {
        Integer ord = jdbcTemplate.queryForObject(
            "SELECT container_ord FROM core_executable WHERE id = ?", Integer.class, executableId);
        return ord == null ? -1 : ord;
    }

    private int plannedMinutesOf(UUID executableId) {
        Integer minutes = jdbcTemplate.queryForObject(
            "SELECT container_planned_minutes FROM core_executable WHERE id = ?",
            Integer.class, executableId);
        return minutes == null ? 0 : minutes;
    }

    private OffsetDateTime startTimeOf(UUID executableId) {
        return jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, executableId);
    }

    private int outboxCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> eventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox_events", String.class);
    }

    private UUID insertTask(String name) {
        return insertExecutable(name, "TASK");
    }

    private UUID insertActivity(String name) {
        return insertExecutable(name, "ACTIVITY");
    }

    private UUID insertExecutable(String name, String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status)
            VALUES (?, ?, ?, ?, 'TODO')
            """, id, USER, name, type);
        return id;
    }

    /**
     * A window of somebody else's authorship, holding one member — the shape the delivery read must
     * leave alone.
     */
    private void insertHeldBlock(String origin, String status, int startHour, UUID member) {
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', ?, ?, ?, ?)
            """, blockId, USER, "Ventana suya", status, origin, at(startHour), at(startHour + 1));
        jdbcTemplate.update(
            "UPDATE core_executable SET container_block_id = ?, container_ord = 0 WHERE id = ?",
            blockId, member);
    }

    /** The write-back projection of the day, read exactly as the delivery would read it. */
    private List<PlannedBlockRecord> plannedBlocks() {
        return repository.loadPlannedBlocksForDay(USER, DAY, ZONE);
    }
}
