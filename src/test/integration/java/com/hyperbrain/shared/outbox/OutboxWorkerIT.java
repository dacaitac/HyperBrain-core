package com.hyperbrain.shared.outbox;

import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@DisplayName("OutboxWorker — transactional outbox relay")
class OutboxWorkerIT {

    private static final String CORE_EVENTS_QUEUE = "core-events";

    @Autowired
    private OutboxWorker outboxWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqsTemplate sqsTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        drainQueue();
    }

    @Test
    @DisplayName("drains an unprocessed event, publishes it to core-events and marks it processed")
    void drains_and_publishes_event() {
        // Given
        UUID id = insertOutboxEvent("TASK", "TaskCompletedEvent", "{\"name\":\"Write tests\"}");

        // When
        int published = outboxWorker.drainBatch();

        // Then
        assertThat(published).isEqualTo(1);
        assertThat(isProcessed(id)).isTrue();

        Optional<Message<String>> message = receiveOne();
        assertThat(message).isPresent();
        assertThat(message.get().getPayload())
            .contains("TaskCompletedEvent")
            .contains("Write tests");
    }

    @Test
    @DisplayName("concurrent workers publish each event exactly once (FOR UPDATE SKIP LOCKED)")
    void concurrent_workers_do_not_double_process() throws Exception {
        // Given a single unprocessed event and two workers racing to drain it
        UUID id = insertOutboxEvent("TASK", "TaskCompletedEvent", "{\"name\":\"single\"}");
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // When both drain concurrently
        try {
            Callable<Integer> drain = outboxWorker::drainBatch;
            List<Future<Integer>> results = pool.invokeAll(List.of(drain, drain));
            int totalPublished = results.get(0).get() + results.get(1).get();

            // Then exactly one worker published it, and only one message reached the queue
            assertThat(totalPublished).isEqualTo(1);
            assertThat(isProcessed(id)).isTrue();
            assertThat(drainQueue()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("purges processed events older than the retention window")
    void purges_expired_processed_events() {
        // Given a processed event backdated beyond the 7-day retention window
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, processed, processed_at, occurred_at)
            VALUES (?, 'TASK', ?, 'TaskCompletedEvent', '{}'::jsonb, true, now() - interval '10 days', now() - interval '10 days')
            """, id, UUID.randomUUID().toString());

        // When
        int purged = outboxWorker.purgeExpired();

        // Then
        assertThat(purged).isEqualTo(1);
        assertThat(existsRow(id)).isFalse();
    }

    private UUID insertOutboxEvent(String aggregateType, String eventType, String payloadJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, ?, ?, ?, ?::jsonb, 'APPLE', now())
            """, id, aggregateType, UUID.randomUUID().toString(), eventType, payloadJson);
        return id;
    }

    private boolean isProcessed(UUID id) {
        Boolean processed = jdbcTemplate.queryForObject(
            "SELECT processed FROM outbox_events WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(processed);
    }

    private boolean existsRow(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private Optional<Message<String>> receiveOne() {
        return sqsTemplate.receive(from -> from
            .queue(CORE_EVENTS_QUEUE)
            .pollTimeout(Duration.ofSeconds(5)), String.class);
    }

    /** Drains every message currently on core-events, returning how many were removed. */
    private int drainQueue() {
        int count = 0;
        while (true) {
            Optional<Message<String>> message = sqsTemplate.receive(from -> from
                .queue(CORE_EVENTS_QUEUE)
                .pollTimeout(Duration.ofSeconds(2)), String.class);
            if (message.isEmpty()) {
                return count;
            }
            count++;
        }
    }
}
