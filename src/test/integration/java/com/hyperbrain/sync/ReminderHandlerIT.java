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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@TestPropertySource(properties = "app.sync.consumer.enabled=true")
@DisplayName("REMINDER handler — full CRUD pipeline (SQS → DB)")
class ReminderHandlerIT {

    private static final String SYNC_QUEUE = "sync-events.fifo";

    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM sync_mappings");
        jdbcTemplate.execute("DELETE FROM core_executable");
        jdbcTemplate.execute("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("CREATED: reminder is persisted as TASK in core_executable with correct fields")
    void created_persists_reminder_as_task() {
        String entityId = "EKReminder-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        send(reminderBody(eventId, "APPLE", entityId, "Buy milk", false, "HyperBrain"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = queryExecutable(entityId);
            assertThat(row).isNotNull();
            assertThat(row.get("name")).isEqualTo("Buy milk");
            assertThat(row.get("type")).isEqualTo("TASK");
            assertThat(row.get("status")).isEqualTo("TODO");
            assertThat(row.get("source_calendar")).isEqualTo("HyperBrain");
        });

        // sync_mapping created
        assertThat(countSyncMapping(entityId)).isEqualTo(1);
        // outbox event written
        assertThat(countOutbox("ReminderSyncedEvent")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CREATED: completed=true maps to status=DONE")
    void created_completed_reminder_has_done_status() {
        String entityId = "EKReminder-" + UUID.randomUUID();

        send(reminderBody(UUID.randomUUID().toString(), "APPLE", entityId, "Paid bills", true, "Finance"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = queryExecutable(entityId);
            assertThat(row).isNotNull();
            assertThat(row.get("status")).isEqualTo("DONE");
        });
    }

    @Test
    @DisplayName("UPDATED: name change updates core_executable (checksum differs)")
    void updated_with_changed_title_updates_executable() {
        String entityId = "EKReminder-" + UUID.randomUUID();

        // First: CREATED
        send(reminderBody(UUID.randomUUID().toString(), "APPLE", entityId, "Old title", false, "List A"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(entityId)).isNotNull());

        // Then: UPDATED with different payload — operation change guarantees different checksum
        send(reminderBody(UUID.randomUUID().toString(), "APPLE", entityId, "New title", false, "List A", "UPDATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = queryExecutable(entityId);
            assertThat(row.get("name")).isEqualTo("New title");
        });
    }

    @Test
    @DisplayName("second event with same checksum (same operation + same payload) is discarded silently")
    void same_checksum_discards_silently() {
        String entityId = "EKReminder-" + UUID.randomUUID();

        // CREATED — stored checksum = SHA-256(entityId + "CREATED" + payload)
        send(reminderBody(UUID.randomUUID().toString(), "APPLE", entityId, "Same title", false, "List", "CREATED"),
            entityId, UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(entityId)).isNotNull());
        int outboxAfterCreated = countOutbox("ReminderSyncedEvent");

        // Second CREATED with same payload content — checksum matches → must be discarded
        send(reminderBody(UUID.randomUUID().toString(), "APPLE", entityId, "Same title", false, "List", "CREATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(countProcessed()).isGreaterThanOrEqualTo(2));
        assertThat(countOutbox("ReminderSyncedEvent")).isEqualTo(outboxAfterCreated);
    }

    @Test
    @DisplayName("DELETED: removes core_executable and sync_mapping, appends outbox event")
    void deleted_removes_records() {
        String entityId = "EKReminder-" + UUID.randomUUID();

        // CREATED
        send(reminderBody(UUID.randomUUID().toString(), "APPLE", entityId, "Task", false, "List"),
            entityId, UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(entityId)).isNotNull());

        // DELETED (no payload)
        send(deletedBody(UUID.randomUUID().toString(), entityId, "REMINDER"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(queryExecutable(entityId)).isNull();
            assertThat(countSyncMapping(entityId)).isEqualTo(0);
            assertThat(countOutbox("ReminderDeletedEvent")).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    @DisplayName("loop protection: HYPERBRAIN_CORE source drops without DB writes")
    void loop_protection_drops_self_originated() {
        String entityId = "EKReminder-" + UUID.randomUUID();

        send(reminderBody(UUID.randomUUID().toString(), "HYPERBRAIN_CORE", entityId, "Self", false, "X"),
            entityId, UUID.randomUUID().toString());

        // Give it time to be (not) processed, verify control event first
        String controlId = "EKReminder-" + UUID.randomUUID();
        send(reminderBody(UUID.randomUUID().toString(), "APPLE", controlId, "Control", false, "X"),
            controlId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(controlId)).isNotNull());

        // Self-originated entity must not have been persisted
        assertThat(queryExecutable(entityId)).isNull();
        assertThat(countSyncMapping(entityId)).isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void send(String body, String groupId, String dedupId) {
        sqsTemplate.send(to -> to
            .queue(SYNC_QUEUE)
            .payload(body)
            .messageGroupId(groupId)
            .messageDeduplicationId(dedupId));
    }

    private Map<String, Object> queryExecutable(String externalId) {
        String sql = """
            SELECT e.name, e.type, e.status, e.source_calendar
            FROM core_executable e
            JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_system = 'APPLE' AND m.external_id = ?
            """;
        var rows = jdbcTemplate.queryForList(sql, externalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int countSyncMapping(String externalId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system='APPLE' AND external_id=?",
            Integer.class, externalId);
        return n == null ? 0 : n;
    }

    private int countOutbox(String eventType) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type=?", Integer.class, eventType);
        return n == null ? 0 : n;
    }

    private int countProcessed() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message", Integer.class);
        return n == null ? 0 : n;
    }

    private static String reminderBody(String eventId, String source, String entityId,
                                       String title, boolean completed, String listName) {
        return reminderBody(eventId, source, entityId, title, completed, listName, "CREATED");
    }

    private static String reminderBody(String eventId, String source, String entityId,
                                       String title, boolean completed, String listName,
                                       String operation) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "%s",
              "entity_type": "REMINDER",
              "entity_id": "%s",
              "operation": "%s",
              "occurred_at": "2026-07-04T15:30:00-05:00",
              "payload": {
                "title": "%s",
                "notes": null,
                "due_date": "2026-07-05T09:00:00-05:00",
                "completed": %s,
                "priority": 0,
                "list_id": "EKCalendar-test",
                "list_name": "%s",
                "alarms": []
              }
            }
            """.formatted(eventId, source, entityId, operation, title, completed, listName);
    }

    private static String deletedBody(String eventId, String entityId, String entityType) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "%s",
              "entity_id": "%s",
              "operation": "DELETED",
              "occurred_at": "2026-07-04T16:00:00-05:00",
              "payload": null
            }
            """.formatted(eventId, entityType, entityId);
    }
}
