package com.hyperbrain.sync;

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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end pipeline tests for the outbound write-back (HU-09c, CA-9/CA-10): a
 * {@code core_executable} change drained from the outbox must produce the right
 * {@code WriteCommand} on {@code apple-commands.fifo}, and the {@code WriteCommandResult}
 * consumed from {@code apple-commands-results.fifo} must close the {@code sync_mapping}.
 */
@IntegrationTest
@TestPropertySource(properties = "app.sync.results-consumer.enabled=true")
@DisplayName("Apple write-back — outbound sync pipeline")
class AppleWriteBackIT {

    private static final String COMMANDS_QUEUE = "apple-commands.fifo";
    private static final String RESULTS_QUEUE = "apple-commands-results.fifo";

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        drainQueue(COMMANDS_QUEUE);
    }

    @Test
    @DisplayName("CREATE: emits WriteCommand(CREATED) and the result closes the sync_mapping (CA-3, CA-9)")
    void create_emits_command_and_result_closes_mapping() throws Exception {
        // Given an unmapped TASK executable and its outbox event
        UUID localId = insertExecutable("TASK", "TODO");
        insertOutboxEvent(localId, "ExecutableCreatedEvent", "SYSTEM", "{}");

        // When the outbox drains
        outboxWorker.drainBatch();

        // Then the WriteCommand lands on apple-commands.fifo with the contract shape
        Message<String> message = receiveOne(COMMANDS_QUEUE).orElseThrow();
        JsonNode command = objectMapper.readTree(message.getPayload());
        assertThat(command.path("command_type").asText()).isEqualTo("REMINDER");
        assertThat(command.path("operation").asText()).isEqualTo("CREATED");
        assertThat(command.path("entity_id").isNull()).isTrue();
        assertThat(command.path("payload").path("title").asText()).isEqualTo("Write tests");
        assertThat(command.path("payload").path("completed").asBoolean()).isFalse();
        assertThat(command.path("payload").path("alarms").isArray()).isTrue();

        String commandId = command.path("command_id").asText();
        assertThat(pendingStatus(commandId)).isEqualTo("PENDING");

        // When SentinelAPI reports the applied result with the fresh EventKit id
        String entityId = "EKReminder-" + UUID.randomUUID();
        sendResult(commandId, "APPLIED", "CREATED", entityId, null);

        // Then the sync_mapping is created and the command row resolved
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countMappings(entityId)).isEqualTo(1);
            assertThat(pendingStatus(commandId)).isEqualTo("APPLIED");
        });
        assertThat(mappingLocalId(entityId)).isEqualTo(localId);
        String checksum = jdbcTemplate.queryForObject(
            "SELECT last_known_checksum FROM sync_mappings WHERE external_id = ?", String.class, entityId);
        assertThat(checksum).isNotBlank();
    }

    @Test
    @DisplayName("UPDATE: mapped executable emits WriteCommand(UPDATED) against the EventKit id (CA-2)")
    void update_emits_updated_command() throws Exception {
        // Given a mapped ACTIVITY executable
        UUID localId = insertExecutable("ACTIVITY", "TODO");
        String entityId = "EKEvent-" + UUID.randomUUID();
        insertMapping(localId, entityId);
        insertOutboxEvent(localId, "ExecutableUpdatedEvent", "NOTION", "{}");

        // When
        outboxWorker.drainBatch();

        // Then
        Message<String> message = receiveOne(COMMANDS_QUEUE).orElseThrow();
        JsonNode command = objectMapper.readTree(message.getPayload());
        assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT");
        assertThat(command.path("operation").asText()).isEqualTo("UPDATED");
        assertThat(command.path("entity_id").asText()).isEqualTo(entityId);
        assertThat(command.path("payload").path("start_time").asText()).isNotBlank();
    }

    @Test
    @DisplayName("DELETE: emits WriteCommand(DELETED) and the result removes the sync_mapping (CA-4)")
    void delete_emits_command_and_result_removes_mapping() throws Exception {
        // Given a mapping whose executable was already deleted
        UUID localId = UUID.randomUUID();
        String entityId = "EKReminder-" + UUID.randomUUID();
        insertMapping(localId, entityId);
        insertOutboxEvent(localId, "ExecutableDeletedEvent", "SYSTEM", "{\"type\":\"TASK\"}");

        // When
        outboxWorker.drainBatch();

        // Then
        Message<String> message = receiveOne(COMMANDS_QUEUE).orElseThrow();
        JsonNode command = objectMapper.readTree(message.getPayload());
        assertThat(command.path("operation").asText()).isEqualTo("DELETED");
        assertThat(command.path("command_type").asText()).isEqualTo("REMINDER");
        assertThat(command.path("entity_id").asText()).isEqualTo(entityId);
        assertThat(command.path("payload").isNull()).isTrue();

        // When SentinelAPI confirms the deletion
        sendResult(command.path("command_id").asText(), "APPLIED", "DELETED", entityId, null);

        // Then the mapping is gone
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countMappings(entityId)).isZero());
    }

    @Test
    @DisplayName("loop protection: source_system=APPLE produces no WriteCommand (CA-10)")
    void apple_sourced_change_is_not_written_back() {
        // Given an Apple-originated outbox event for a mapped executable
        UUID localId = insertExecutable("TASK", "TODO");
        insertMapping(localId, "EKReminder-loop");
        insertOutboxEvent(localId, "ExecutableUpdatedEvent", "APPLE", "{}");

        // When
        outboxWorker.drainBatch();

        // Then nothing reaches apple-commands.fifo and no command row is logged
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
        Integer commands = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_write_commands", Integer.class);
        assertThat(commands).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertExecutable(String type, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time, source_calendar)
            VALUES (?, ?, 'Write tests', ?, ?, now(), now() + interval '1 hour', 'HyperBrain')
            """, id, DataFixture.SYSTEM_USER_ID, type, status);
        return id;
    }

    private void insertMapping(UUID localId, String externalId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'APPLE', ?, 'previous', 'SYNCED', now())
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, externalId);
    }

    private void insertOutboxEvent(UUID localId, String eventType, String sourceSystem, String payload) {
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, 'CORE_EXECUTABLE', ?, ?, ?::jsonb, ?, now())
            """, UUID.randomUUID(), localId.toString(), eventType, payload, sourceSystem);
    }

    private void sendResult(String commandId, String status, String operation, String entityId, String error) {
        String body = """
            {
              "schema_version": "1",
              "command_id": "%s",
              "status": "%s",
              "operation": "%s",
              "entity_id": %s,
              "error": %s,
              "applied_at": "2026-07-06T12:00:00-05:00"
            }
            """.formatted(commandId, status, operation,
                entityId != null ? "\"" + entityId + "\"" : "null",
                error != null ? "\"" + error + "\"" : "null");
        sqsTemplate.send(to -> to
            .queue(RESULTS_QUEUE)
            .payload(body)
            .messageGroupId(commandId)
            .messageDeduplicationId(UUID.randomUUID().toString()));
    }

    private String pendingStatus(String commandId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM sync_write_commands WHERE command_id = ?::uuid", String.class, commandId);
    }

    private UUID mappingLocalId(String externalId) {
        return jdbcTemplate.queryForObject(
            "SELECT local_id FROM sync_mappings WHERE external_system = 'APPLE' AND external_id = ?",
            UUID.class, externalId);
    }

    private int countMappings(String externalId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_id = ?", Integer.class, externalId);
        return count == null ? 0 : count;
    }

    private Optional<Message<String>> receiveOne(String queue) {
        return sqsTemplate.receive(from -> from
            .queue(queue)
            .pollTimeout(Duration.ofSeconds(5)), String.class);
    }

    /** Removes any leftover messages so a previous test class cannot leak into assertions. */
    private void drainQueue(String queue) {
        while (receiveOne(queue).isPresent()) {
            // drain until empty
        }
    }
}
