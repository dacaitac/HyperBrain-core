package com.hyperbrain.shared.outbox;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the virtual-thread execution contract of the outbox pipeline (HU-14 CA-14):
 * propagators run on the dedicated virtual-thread executor — concurrently when more than one
 * is eligible (CA-13) — and the application {@link TaskScheduler} that triggers the periodic
 * drain ({@code OutboxScheduler}) dispatches on virtual threads
 * ({@code spring.threads.virtual.enabled=true}).
 *
 * <p>The drain is driven manually: enabling the real scheduler in a cached test context would
 * keep polling in the background and steal outbox events from the other IT classes that share
 * the database.
 */
@IntegrationTest
@DisplayName("Outbox propagation — virtual threads end to end (CA-13/CA-14)")
class OutboxPropagationIT {

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        ProbePropagator probePropagator() {
            return new ProbePropagator();
        }
    }

    /** Records the thread kind and name of every propagation it receives. */
    static final class ProbePropagator implements IEventPropagator {

        private final CopyOnWriteArrayList<Boolean> virtualFlags = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> threadNames = new CopyOnWriteArrayList<>();

        @Override
        public ExternalSystem target() {
            return ExternalSystem.NOTION;
        }

        @Override
        public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
            return true;
        }

        @Override
        public void propagate(OutboxEvent event) {
            virtualFlags.add(Thread.currentThread().isVirtual());
            threadNames.add(Thread.currentThread().getName());
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxWorker outboxWorker;
    @Autowired private TaskScheduler taskScheduler;
    @Autowired private ProbePropagator probe;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        probe.virtualFlags.clear();
        probe.threadNames.clear();
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("CA-14: propagators run on the dedicated virtual-thread executor during the drain")
    void propagators_run_on_virtual_threads() {
        // Given one unprocessed event eligible for the probe
        insertOutboxEvent();

        // When
        int published = outboxWorker.drainBatch();

        // Then the probe ran on a virtual thread of the outbox propagation executor
        assertThat(published).isEqualTo(1);
        assertThat(probe.virtualFlags).containsExactly(true);
        assertThat(probe.threadNames.get(0)).startsWith("outbox-propagation-");
    }

    @Test
    @DisplayName("CA-14: the application TaskScheduler (OutboxScheduler trigger) dispatches on virtual threads")
    void task_scheduler_dispatches_on_virtual_threads() throws Exception {
        // Given a one-off task on the same scheduler infrastructure @Scheduled uses
        AtomicReference<Boolean> virtualFlag = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // When
        taskScheduler.schedule(() -> {
            virtualFlag.set(Thread.currentThread().isVirtual());
            done.countDown();
        }, Instant.now());

        // Then
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(virtualFlag.get()).isTrue();
    }

    private void insertOutboxEvent() {
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, 'CORE_EXECUTABLE', ?, 'ExecutableUpdatedEvent', '{}'::jsonb, 'SYSTEM', now())
            """, UUID.randomUUID(), UUID.randomUUID().toString());
    }
}
