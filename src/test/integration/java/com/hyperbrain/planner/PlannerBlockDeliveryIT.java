package com.hyperbrain.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.domain.port.in.ExecutableContainmentService;
import com.hyperbrain.planner.application.PlannerBlockMaterializer;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.shared.outbox.OutboxWorker;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executable specification that block delivery does not duplicate calendar events (#15) — ported
 * from the two write-back tests of the planner's private delivery channel when that channel was pruned.
 *
 * <p>Nothing about the guarantee changed, only the road: a block is a {@code TIME_BLOCK} executable, so
 * it reaches Apple through the standard propagator by the same type routing every other executable
 * uses. A surviving block keeps its id and therefore its mapping, so it is UPDATED rather than
 * duplicated; a genuinely new one is CREATED; a withdrawn one is DELETED. These assertions are the
 * regression net for the bug that once cost a day of duplicated events, which is why they outlived the
 * code they were written against.
 */
@IntegrationTest
@DisplayName("Planner block delivery — CREATE/UPDATE/DELETE without duplicates (#15, ADR-040 D15)")
class PlannerBlockDeliveryIT {

    private static final String COMMANDS_QUEUE = "apple-commands.fifo";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final String SLOT = "GOAL_MORNING";
    private static final String CRITERION = "Sleep Score 80 -> quota 3";

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private PlannerBlockMaterializer materializer;
    @Autowired private ExecutableContainmentService containment;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
        drainQueue();
    }

    @Test
    @DisplayName("a new block reaches apple-commands.fifo as a time-boxed calendar event")
    void a_new_block_is_created_as_a_calendar_event() throws Exception {
        UUID task = insertTask("Write the report");

        materialize(List.of(block(task, List.of(), 9, 11)));
        outboxWorker.drainBatch();

        JsonNode command = firstCalendarCommand();
        assertThat(command.path("operation").asText()).isEqualTo("CREATED");
        assertThat(command.path("entity_id").isNull()).isTrue();
        JsonNode payload = command.path("payload");
        // A time-boxed event, not a reminder: the block's duration must survive the trip.
        assertThat(payload.path("start_time").asText()).startsWith("2026-07-10T09:00");
        assertThat(payload.path("end_time").asText()).startsWith("2026-07-10T11:00");
        assertThat(payload.has("due_date")).isFalse();
    }

    @Test
    @DisplayName("a block that already reached Apple is UPDATED against its event, never duplicated")
    void a_surviving_block_is_updated_not_duplicated() throws Exception {
        UUID task = insertTask("Write the report");
        materialize(List.of(block(task, List.of(), 9, 11)));
        UUID blockId = blockId();
        String eventId = "EKEvent-" + UUID.randomUUID();
        mapToApple(blockId, eventId);
        jdbcTemplate.update("DELETE FROM outbox_events");
        drainQueue();

        // A replan moves the same window: same slot, so the same block, so the same mapping.
        materialize(List.of(block(task, List.of(), 14, 16)));
        outboxWorker.drainBatch();

        assertThat(blockId()).isEqualTo(blockId);
        JsonNode command = firstCalendarCommand();
        assertThat(command.path("operation").asText()).isEqualTo("UPDATED");
        assertThat(command.path("entity_id").asText()).isEqualTo(eventId);
        assertThat(commandCountForLocalId(blockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("changing the membership still UPDATEs the same event: identity is not the membership")
    void a_membership_change_updates_the_same_event() throws Exception {
        UUID staying = insertTask("Staying");
        UUID leaving = insertTask("Leaving the theme");
        UUID joining = insertTask("Joining the theme");
        materialize(List.of(block(staying, List.of(leaving), 9, 11)));
        UUID blockId = blockId();
        String eventId = "EKEvent-" + UUID.randomUUID();
        mapToApple(blockId, eventId);
        jdbcTemplate.update("DELETE FROM outbox_events");
        drainQueue();

        materialize(List.of(block(staying, List.of(joining), 9, 11).retimed(
            at(9), at(11), "Membership swapped")));
        outboxWorker.drainBatch();

        // The id survived a member entering and another leaving — had identity been derived from the
        // membership, this would have minted a new id and duplicated the calendar event.
        assertThat(blockId()).isEqualTo(blockId);
        // Exactly one command was logged against the block, and it UPDATEs the event it already had.
        assertThat(commandCountForLocalId(blockId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
            "SELECT command_type, operation, entity_id FROM sync_write_commands WHERE local_id = ?",
            blockId))
            .containsEntry("command_type", "CALENDAR_EVENT")
            .containsEntry("operation", "UPDATED")
            .containsEntry("entity_id", eventId);
    }

    @Test
    @DisplayName("the block's note carries what it holds, why, the day's criterion and the deletion warning")
    void the_block_note_survives_the_channel_it_used_to_live_in() throws Exception {
        UUID first = insertTask("Write the report");
        UUID second = insertTask("Review PRs");

        materialize(List.of(block(first, List.of(second), 9, 11)));
        outboxWorker.drainBatch();

        String notes = firstCalendarCommand().path("payload").path("notes").asText();
        assertThat(notes)
            .contains(PlannerBlockMaterializer.MEMBERS_NOTE_HEADING)
            .contains("Write the report")
            .contains("Review PRs")
            .contains(CRITERION)
            .contains(PlannerBlockMaterializer.DELETION_NOTE);
    }

    @Test
    @DisplayName("a withdrawn block is DELETED on Apple, and its members survive it")
    void a_withdrawn_block_is_deleted() throws Exception {
        UUID task = insertTask("Write the report");
        materialize(List.of(block(task, List.of(), 9, 11)));
        UUID blockId = blockId();
        String eventId = "EKEvent-" + UUID.randomUUID();
        mapToApple(blockId, eventId);
        jdbcTemplate.update("DELETE FROM outbox_events");
        drainQueue();

        materialize(List.of());
        outboxWorker.drainBatch();

        JsonNode command = drainCommands().stream()
            .filter(c -> "DELETED".equals(c.path("operation").asText()))
            .findFirst().orElseThrow();
        assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT");
        assertThat(command.path("entity_id").asText()).isEqualTo(eventId);
        // Letting go happens before deleting, so the task outlives its block (ADR-040 D10).
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE id = ?", Integer.class, task)).isEqualTo(1);
    }

    @Test
    @DisplayName("a retried drain re-emits the same command, never a second one")
    void a_retried_drain_is_idempotent() {
        UUID task = insertTask("Write the report");
        materialize(List.of(block(task, List.of(), 9, 11)));
        UUID blockId = blockId();

        outboxWorker.drainBatch();
        jdbcTemplate.update("UPDATE outbox_events SET processed = false, processed_at = NULL");
        outboxWorker.drainBatch();

        assertThat(commandCountForLocalId(blockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a plan that moved nothing announces nothing: no command, no outbox row")
    void an_unchanged_plan_emits_no_command() {
        UUID task = insertTask("Write the report");
        materialize(List.of(block(task, List.of(), 9, 11)));
        outboxWorker.drainBatch();
        jdbcTemplate.update("DELETE FROM outbox_events");
        drainQueue();

        materialize(List.of(block(task, List.of(), 9, 11)));
        outboxWorker.drainBatch();

        assertThat(drainCommands()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void materialize(List<AgendaBlock> blocks) {
        transactionTemplate.executeWithoutResult(status -> materializer.materialize(
            USER, DAY, UTC, blocks, DAY.atStartOfDay(UTC).toOffsetDateTime(), CRITERION));
    }

    private static AgendaBlock block(UUID anchor, List<UUID> others, int startHour, int endHour) {
        return new AgendaBlock(anchor, at(startHour), at(endHour), false, false,
            "Laid on the goal window", others, null, SLOT);
    }

    private static OffsetDateTime at(int hour) {
        return OffsetDateTime.of(2026, 7, 10, hour, 0, 0, 0, UTC);
    }

    private UUID blockId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM core_executable WHERE type = 'TIME_BLOCK' AND origin = 'PLANNER'",
            UUID.class);
    }

    private UUID insertTask(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status)
            VALUES (?, ?, ?, 'TASK', 'TODO')
            """, id, USER, name);
        return id;
    }

    private void mapToApple(UUID localId, String externalId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id,
                                       last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'APPLE', ?, 'previous', 'SYNCED', now())
            """, UUID.randomUUID(), USER, localId, externalId);
    }

    private int commandCountForLocalId(UUID localId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_write_commands WHERE local_id = ?", Integer.class, localId);
        return count == null ? 0 : count;
    }

    private JsonNode firstCalendarCommand() {
        return drainCommands().stream()
            .filter(c -> "CALENDAR_EVENT".equals(c.path("command_type").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CALENDAR_EVENT command was emitted"));
    }

    private List<JsonNode> drainCommands() {
        List<JsonNode> commands = new ArrayList<>();
        Optional<Message<String>> message;
        while ((message = receiveOne()).isPresent()) {
            try {
                commands.add(objectMapper.readTree(message.get().getPayload()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("Unreadable command payload", ex);
            }
        }
        return commands;
    }

    private Optional<Message<String>> receiveOne() {
        return sqsTemplate.receive(from -> from
            .queue(COMMANDS_QUEUE)
            .pollTimeout(Duration.ofSeconds(5)), String.class);
    }

    private void drainQueue() {
        while (receiveOne().isPresent()) {
            // drain until empty
        }
    }
}
