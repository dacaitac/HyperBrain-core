package com.hyperbrain.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the replan reconciliation of the morning write-back (#15): when a day is regenerated, a
 * surviving block keeps its stable id and therefore its Apple mapping, so the propagator emits an
 * {@code UPDATE} of the existing EKEvent instead of a duplicate {@code CREATE}; a block that dropped
 * out of the plan is carried in {@code removed_block_ids} and deleted from Apple (its mapping closed by
 * the result loop); a genuinely new block is created. The Reminder of the scheduled executable is never
 * touched — the write-back mirrors only the calendar-event projection of the blocks.
 */
@IntegrationTest
@TestPropertySource(properties = "app.sync.results-consumer.enabled=true")
@DisplayName("AgendaBlockPropagator — replan reconciliation UPDATE/DELETE/CREATE (#15)")
class AgendaBlockReplanWriteBackIT {

    private static final String COMMANDS_QUEUE = "apple-commands.fifo";
    private static final String RESULTS_QUEUE = "apple-commands-results.fifo";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final OffsetDateTime BLOCK_START = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC);
    private static final OffsetDateTime BLOCK_END = OffsetDateTime.of(2026, 7, 10, 10, 0, 0, 0, UTC);
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, UTC);

    @Autowired private OutboxWorker outboxWorker;
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
        drainQueue(COMMANDS_QUEUE);
    }

    @Test
    @DisplayName("a surviving mapped block is UPDATED against its EKEvent, never duplicated")
    void surviving_block_is_updated_not_duplicated() throws Exception {
        // Given a PLANNED block already delivered to Apple (mapped to an EKEvent)
        UUID executable = insertExecutable("Deep work");
        UUID blockId = insertPlannedBlock(executable, "Reserved as the WIG lead measure");
        String eventId = "EKEvent-" + UUID.randomUUID();
        insertBlockMapping(blockId, eventId);
        insertAgendaBlockEvent(List.of());

        // When the replan's write-back drains
        outboxWorker.drainBatch();

        // Then the block updates the same EKEvent (no fresh CREATE → no duplicate)
        JsonNode command = objectMapper.readTree(receiveOne(COMMANDS_QUEUE).orElseThrow().getPayload());
        assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT");
        assertThat(command.path("operation").asText()).isEqualTo("UPDATED");
        assertThat(command.path("entity_id").asText()).isEqualTo(eventId);

        // And the command is logged under the block id, so exactly one command exists for it
        assertThat(commandCountForLocalId(blockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a block dropped from the plan is DELETED on Apple and its mapping is closed (no orphan)")
    void removed_block_is_deleted_and_mapping_closed() throws Exception {
        // Given a block that was delivered on a prior run but is absent from the new plan: only its
        // sync_mapping survives (the core_time_block row is already gone after reconciliation).
        UUID removedBlockId = UUID.randomUUID();
        String eventId = "EKEvent-" + UUID.randomUUID();
        insertBlockMapping(removedBlockId, eventId);
        insertAgendaBlockEvent(List.of(removedBlockId));

        // When the replan's write-back drains
        outboxWorker.drainBatch();

        // Then a DELETE of the mapped EKEvent is emitted
        JsonNode command = objectMapper.readTree(receiveOne(COMMANDS_QUEUE).orElseThrow().getPayload());
        assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT");
        assertThat(command.path("operation").asText()).isEqualTo("DELETED");
        assertThat(command.path("entity_id").asText()).isEqualTo(eventId);
        assertThat(command.path("payload").isNull()).isTrue();

        // And once Apple confirms the delete, the mapping is removed — no orphan left behind
        sendResult(command.path("command_id").asText(), eventId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countMappings(eventId)).isZero());
    }

    @Test
    @DisplayName("a genuinely new block (no mapping) is CREATED")
    void new_block_is_created() throws Exception {
        UUID executable = insertExecutable("Fresh task");
        insertPlannedBlock(executable, "Ranked by priority");
        insertAgendaBlockEvent(List.of());

        outboxWorker.drainBatch();

        JsonNode command = objectMapper.readTree(receiveOne(COMMANDS_QUEUE).orElseThrow().getPayload());
        assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT");
        assertThat(command.path("operation").asText()).isEqualTo("CREATED");
        assertThat(command.path("entity_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("a retried drain re-emits the same deterministic command, never a second one")
    void retried_drain_is_idempotent() {
        UUID executable = insertExecutable("Deep work");
        UUID blockId = insertPlannedBlock(executable, "Reserved as the WIG lead measure");
        String eventId = "EKEvent-" + UUID.randomUUID();
        insertBlockMapping(blockId, eventId);
        insertAgendaBlockEvent(List.of());

        outboxWorker.drainBatch();
        // Simulate an at-least-once redelivery of the same outbox event.
        jdbcTemplate.update("UPDATE outbox_events SET processed = false, processed_at = NULL");
        outboxWorker.drainBatch();

        // The deterministic command id makes the second emission an upsert of the same row.
        assertThat(commandCountForLocalId(blockId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the Reminder of the scheduled executable is never written: only CALENDAR_EVENTs flow")
    void executable_reminder_is_never_touched() {
        // Given a block scheduling an executable that itself has an Apple Reminder mapping
        UUID executable = insertExecutable("Deep work");
        UUID blockId = insertPlannedBlock(executable, "Ranked by priority");
        String reminderId = "EKReminder-" + UUID.randomUUID();
        insertBlockMapping(executable, reminderId);
        insertAgendaBlockEvent(List.of());

        // When the write-back drains
        outboxWorker.drainBatch();

        // Then every emitted command targets the block, never the executable, and is a CALENDAR_EVENT
        List<JsonNode> commands = drainCommands();
        assertThat(commands).isNotEmpty();
        assertThat(commands).allSatisfy(command ->
            assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT"));
        assertThat(commandCountForLocalId(executable)).isZero();
        assertThat(commandCountForLocalId(blockId)).isEqualTo(1);
        // The executable's own Reminder mapping is left intact (input/deadline, out of scope).
        assertThat(countMappings(reminderId)).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertExecutable(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, system_generated)
            VALUES (?, ?, ?, 'TASK', 'TODO', true)
            """, id, USER, name);
        return id;
    }

    private UUID insertPlannedBlock(UUID executableId, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin, reason)
            VALUES (?, ?, ?, ?, 'PLANNED', 'PLANNER', ?)
            """, id, executableId, BLOCK_START, BLOCK_END, reason);
        return id;
    }

    private void insertBlockMapping(UUID localId, String externalId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'APPLE', ?, 'previous', 'SYNCED', now())
            """, UUID.randomUUID(), USER, localId, externalId);
    }

    private void insertAgendaBlockEvent(List<UUID> removedBlockIds) {
        StringBuilder removed = new StringBuilder("[");
        for (int i = 0; i < removedBlockIds.size(); i++) {
            if (i > 0) {
                removed.append(',');
            }
            removed.append('"').append(removedBlockIds.get(i)).append('"');
        }
        removed.append(']');
        String payload = """
            {"user_id":"%s","target_day":"2026-07-10","zone_id":"UTC","energy_criterion":"Sleep Score 80 -> margin +0.6 -> quota 3","removed_block_ids":%s}
            """.formatted(USER, removed);
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, 'AGENDA_BLOCK', ?, 'AgendaBlockPlannedEvent', ?::jsonb, 'SYSTEM', ?)
            """, UUID.randomUUID(), USER.toString(), payload, NOON);
    }

    private void sendResult(String commandId, String entityId) {
        String body = """
            {
              "schema_version": "1",
              "command_id": "%s",
              "status": "APPLIED",
              "operation": "DELETED",
              "entity_id": "%s",
              "error": null,
              "applied_at": "2026-07-10T12:00:00+00:00"
            }
            """.formatted(commandId, entityId);
        sqsTemplate.send(to -> to
            .queue(RESULTS_QUEUE)
            .payload(body)
            .messageGroupId(commandId)
            .messageDeduplicationId(UUID.randomUUID().toString()));
    }

    private int commandCountForLocalId(UUID localId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_write_commands WHERE local_id = ?", Integer.class, localId);
        return count == null ? 0 : count;
    }

    private int countMappings(String externalId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_id = ?", Integer.class, externalId);
        return count == null ? 0 : count;
    }

    private List<JsonNode> drainCommands() {
        List<JsonNode> commands = new ArrayList<>();
        Optional<Message<String>> message;
        while ((message = receiveOne(COMMANDS_QUEUE)).isPresent()) {
            try {
                commands.add(objectMapper.readTree(message.get().getPayload()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("Unreadable command payload", ex);
            }
        }
        return commands;
    }

    private Optional<Message<String>> receiveOne(String queue) {
        return sqsTemplate.receive(from -> from
            .queue(queue)
            .pollTimeout(Duration.ofSeconds(5)), String.class);
    }

    private void drainQueue(String queue) {
        while (receiveOne(queue).isPresent()) {
            // drain until empty
        }
    }
}
