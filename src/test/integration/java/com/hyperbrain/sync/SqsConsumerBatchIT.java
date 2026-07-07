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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies that the SqsMessageListenerContainerFactory batch configuration (CA-6) enables
 * concurrent processing of messages from distinct MessageGroupIds (CA-7, CA-8).
 *
 * <p>Ten messages are published with distinct entity IDs (→ distinct MessageGroupIds on the
 * FIFO queue). With batch polling (maxMessagesPerPoll=10) and the virtual-thread executor,
 * all ten are fetched in a single SQS call and processed in parallel. The test asserts that
 * the total wall-clock time is well below what serial processing would require.
 */
@IntegrationTest
@TestPropertySource(properties = "app.sync.consumer.enabled=true")
@DisplayName("SqsConsumer — batch polling + concurrent processing (CA-8)")
class SqsConsumerBatchIT {

    private static final String SYNC_QUEUE = "sync-events.fifo";
    private static final int BATCH_SIZE = 10;

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
    @DisplayName("fetches and processes 10 messages concurrently within a single poll cycle")
    void processes_batch_of_ten_messages_concurrently() {
        List<String> eventIds  = new ArrayList<>(BATCH_SIZE);
        List<String> entityIds = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            eventIds.add(UUID.randomUUID().toString());
            entityIds.add("EKReminder-batch-" + UUID.randomUUID());
        }

        Instant start = Instant.now();
        for (int i = 0; i < BATCH_SIZE; i++) {
            send(reminderBody(eventIds.get(i), "APPLE", entityIds.get(i)),
                 entityIds.get(i),
                 UUID.randomUUID().toString());
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (String eventId : eventIds) {
                assertThat(countProcessed(eventId))
                    .as("event %s should be in processed_message", eventId)
                    .isEqualTo(1);
            }
        });

        // With batch polling + parallel dispatch the total time is bounded by ~1 message's
        // processing time plus overhead. Serial processing of 10 messages through separate
        // SQS polls would take significantly longer (≥ 10 × polling round-trip).
        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed)
            .as("10 messages processed concurrently should finish well under 10 s")
            .isLessThan(Duration.ofSeconds(10));
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

    private static String reminderBody(String eventId, String sourceSystem, String entityId) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "%s",
              "entity_type": "REMINDER",
              "entity_id": "%s",
              "operation": "CREATED",
              "occurred_at": "2026-07-07T10:00:00-05:00",
              "payload": {
                "title": "Batch IT reminder",
                "notes": null,
                "due_date": "2026-07-08T09:00:00-05:00",
                "completed": false,
                "priority": 0,
                "list_id": "EKCalendar-batch-test",
                "list_name": "HyperBrain",
                "alarms": []
              }
            }
            """.formatted(eventId, sourceSystem, entityId);
    }
}
