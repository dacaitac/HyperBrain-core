package com.hyperbrain.sync;

import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.ReminderEventHandler;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@TestPropertySource(properties = "app.sync.consumer.enabled=true")
@DisplayName("SqsConsumer — inbound sync pipeline")
class SqsConsumerIT {

    private static final String SYNC_QUEUE = "sync-events.fifo";

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Spy verifies handler invocation counts (dedup / loop protection) without altering behavior.
    @MockitoSpyBean
    private ReminderEventHandler reminderEventHandler;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM processed_message");
    }

    @Test
    @DisplayName("consumes a REMINDER event, routes it and records it in processed_message")
    void consumes_and_records_reminder_event() {
        // Given
        String eventId = UUID.randomUUID().toString();
        String entityId = "EKReminder-" + UUID.randomUUID();

        // When
        send(reminderBody(eventId, "APPLE", entityId), entityId, UUID.randomUUID().toString());

        // Then
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            verify(reminderEventHandler, times(1)).handle(eventWithId(eventId));
            assertThat(countProcessed(eventId)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("deduplicates a redelivered event by event_id (handler invoked once)")
    void deduplicates_redelivered_event() {
        // Given — same event_id, distinct SQS dedup ids so FIFO delivers both copies
        String eventId = UUID.randomUUID().toString();
        String entityId = "EKReminder-" + UUID.randomUUID();
        String body = reminderBody(eventId, "APPLE", entityId);

        // When
        send(body, entityId, UUID.randomUUID().toString());
        send(body, entityId, UUID.randomUUID().toString());

        // Then — dedup store collapses the duplicate; the handler runs exactly once
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countProcessed(eventId)).isEqualTo(1));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            verify(reminderEventHandler, times(1)).handle(eventWithId(eventId)));
    }

    @Test
    @DisplayName("drops a self-originated event (loop protection) without processing it")
    void drops_self_originated_event() {
        // Given — an event that looped back from the Core's own propagation
        String selfEventId = UUID.randomUUID().toString();
        String selfEntity = "EKReminder-" + UUID.randomUUID();
        // And a control event that must still be processed
        String controlId = UUID.randomUUID().toString();
        String controlEntity = "EKReminder-" + UUID.randomUUID();

        // When
        send(reminderBody(selfEventId, "HYPERBRAIN_CORE", selfEntity), selfEntity, UUID.randomUUID().toString());
        send(reminderBody(controlId, "APPLE", controlEntity), controlEntity, UUID.randomUUID().toString());

        // Then — once the control event lands, the self event left no trace and reached no handler
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countProcessed(controlId)).isEqualTo(1));
        assertThat(countProcessed(selfEventId)).isZero();
        verify(reminderEventHandler, never()).handle(eventWithId(selfEventId));
    }

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

    private static SentinelEvent eventWithId(String eventId) {
        return argThat(event -> event != null && eventId.equals(event.eventId()));
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
                "list_id": "EKCalendar-test",
                "list_name": "HyperBrain"
              }
            }
            """.formatted(eventId, sourceSystem, entityId);
    }
}
