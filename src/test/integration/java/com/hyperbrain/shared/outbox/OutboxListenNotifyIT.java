package com.hyperbrain.shared.outbox;

import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for the PostgreSQL LISTEN/NOTIFY drain trigger (TD-10 CA-5).
 *
 * <p>The backup scheduler is disabled; the LISTEN/NOTIFY listener is explicitly enabled
 * (overriding the integration-test profile default). Appending via {@link OutboxRepository}
 * emits NOTIFY outbox_drain in the same transaction as the INSERT — the drain must complete
 * within 500 ms without any active poll.
 */
@IntegrationTest
@TestPropertySource(properties = {
    "app.outbox.notify-listen-enabled=true",
    "app.outbox.scheduling-enabled=false"
})
@DisplayName("OutboxListenConnection — LISTEN/NOTIFY drain trigger")
class OutboxListenNotifyIT {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    @DisplayName("NOTIFY outbox_drain triggers drain within 500 ms — no backup poll active")
    void notify_triggers_drain_without_poll() {
        // Given — append() emits pg_notify('outbox_drain') in the same (auto-commit) statement
        OutboxEvent event = new OutboxEvent(
            UUID.randomUUID(),
            "TASK",
            UUID.randomUUID().toString(),
            "TaskCompletedEvent",
            "{\"name\":\"listen test\"}",
            "HYPERBRAIN_CORE",
            OffsetDateTime.now()
        );
        outboxRepository.append(event);

        // Then — drain must complete within 500 ms, driven solely by LISTEN/NOTIFY
        await()
            .atMost(Duration.ofMillis(500))
            .pollInterval(Duration.ofMillis(10))
            .untilAsserted(() -> assertThat(isProcessed(event.id())).isTrue());
    }

    private boolean isProcessed(UUID id) {
        Boolean processed = jdbcTemplate.queryForObject(
            "SELECT processed FROM outbox_events WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(processed);
    }
}
