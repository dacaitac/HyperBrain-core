package com.hyperbrain.shared.outbox;

import com.hyperbrain.shared.outbox.infrastructure.OutboxListenConnection;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 *
 * <p>The listener is stopped in {@code @AfterAll}: this is the only cached context with a live
 * LISTEN connection, and NOTIFY is database-wide — if it survived the class it would silently
 * drain outbox rows staged by later manual-drain ITs (an APPLE-origin event is consumed with zero
 * trace here: Apple suppressed by loop protection, Notion disabled), which raced
 * {@code E2ETransversalIT}. {@code @DirtiesContext} is not an option: closing the context stops
 * the shared static Testcontainers for the rest of the suite.
 */
@IntegrationTest
@TestPropertySource(properties = {
    "app.outbox.notify-listen-enabled=true",
    "app.outbox.scheduling-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OutboxListenConnection — LISTEN/NOTIFY drain trigger")
class OutboxListenNotifyIT {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxListenConnection listenConnection;

    @AfterAll
    void stopListenerSoTheCachedContextCannotStealLaterOutboxRows() {
        listenConnection.stop();
    }

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
