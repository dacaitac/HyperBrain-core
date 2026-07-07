package com.hyperbrain.sync;

import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pipeline-level tests for the inbound sync consumer: deduplication and loop protection.
 * Verifies behavior via DB state (processed_message + core_executable) rather than spy-based
 * invocation counts, to avoid competing-listener issues caused by MockitoSpyBean creating
 * a separate Spring context with its own SQS listener.
 */
@IntegrationTest
@TestPropertySource(properties = "app.sync.consumer.enabled=true")
@DisplayName("SqsConsumer — inbound sync pipeline")
class SqsConsumerIT {

    private static final String SYNC_QUEUE = "sync-events.fifo";

    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("consumes a REMINDER event, routes it and records it in processed_message and core_executable")
    void consumes_and_records_reminder_event() {
        String eventId  = UUID.randomUUID().toString();
        String entityId = "EKReminder-" + UUID.randomUUID();

        send(reminderBody(eventId, "APPLE", entityId), entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countProcessed(eventId)).isEqualTo(1);
            assertThat(countExecutable(entityId)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("deduplicates a redelivered event by event_id (DB written once)")
    void deduplicates_redelivered_event() {
        String eventId  = UUID.randomUUID().toString();
        String entityId = "EKReminder-" + UUID.randomUUID();
        String body = reminderBody(eventId, "APPLE", entityId);

        // Two deliveries with the same event_id but distinct SQS dedup ids
        send(body, entityId, UUID.randomUUID().toString());
        send(body, entityId, UUID.randomUUID().toString());

        // processed_message = 1 (second delivery discarded)
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countProcessed(eventId)).isEqualTo(1));
        // Only one core_executable row for this entity
        assertThat(countExecutable(entityId)).isEqualTo(1);
    }

    @Test
    @DisplayName("drops a self-originated event (loop protection) without processing it")
    void drops_self_originated_event() {
        String selfEventId   = UUID.randomUUID().toString();
        String selfEntity    = "EKReminder-" + UUID.randomUUID();
        String controlId     = UUID.randomUUID().toString();
        String controlEntity = "EKReminder-" + UUID.randomUUID();

        send(reminderBody(selfEventId, "HYPERBRAIN_CORE", selfEntity), selfEntity, UUID.randomUUID().toString());
        send(reminderBody(controlId,   "APPLE",           controlEntity), controlEntity, UUID.randomUUID().toString());

        // Control event processed → sync consumer is alive
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countProcessed(controlId)).isEqualTo(1));

        // Self-originated event: not in processed_message and not in core_executable
        assertThat(countProcessed(selfEventId)).isZero();
        assertThat(countExecutable(selfEntity)).isZero();
    }

    @Test
    @DisplayName("acknowledges a NOTION webhook envelope with dedup and no routing (pre-HU-14)")
    void acknowledges_notion_envelope_without_routing() {
        String messageId = UUID.randomUUID().toString();
        String body = notionBody(messageId);

        // Two deliveries with the same message_id but distinct SQS dedup ids
        send(body, "notion-smoke-entity", UUID.randomUUID().toString());
        send(body, "notion-smoke-entity", UUID.randomUUID().toString());

        // Acknowledged exactly once, and nothing was routed into the domain
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countProcessed(messageId)).isEqualTo(1));
        Integer executables = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable", Integer.class);
        assertThat(executables).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void send(String body, String groupId, String dedupId) {
        sqsTemplate.send(to -> to
            .queue(SYNC_QUEUE)
            .payload(body)
            .messageGroupId(groupId)
            .messageDeduplicationId(dedupId));
    }

    private int countProcessed(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message WHERE message_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int countExecutable(String externalId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM core_executable e
            JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_system='APPLE' AND m.external_id=?
            """, Integer.class, externalId);
        return count == null ? 0 : count;
    }

    private static String notionBody(String messageId) {
        return """
            {
              "source_system": "NOTION",
              "message_id": "%s",
              "timestamp": "2026-07-07T10:00:00Z",
              "payload": {
                "id": "%s",
                "type": "page.content_updated",
                "entity": { "id": "notion-smoke-entity", "type": "page" }
              }
            }
            """.formatted(messageId, messageId);
    }

    private static String reminderBody(String eventId, String sourceSystem, String entityId) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "%s",
              "entity_type": "REMINDER",
              "entity_id": "%s",
              "operation": "CREATED",
              "occurred_at": "2026-07-04T15:30:00-05:00",
              "payload": {
                "title": "Integration test reminder",
                "notes": null,
                "due_date": "2026-07-05T09:00:00-05:00",
                "completed": false,
                "priority": 0,
                "list_id": "EKCalendar-test",
                "list_name": "HyperBrain",
                "alarms": []
              }
            }
            """.formatted(eventId, sourceSystem, entityId);
    }
}
