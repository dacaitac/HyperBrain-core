package com.hyperbrain.shared.outbox;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.IEventPublisher;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transactional Outbox relay: drains {@code outbox_events}, hands each event to the
 * {@link IEventPublisher} and then to every eligible {@link IEventPropagator} (satellite
 * write-back, HU-09c/HU-10/HU-14). The single component that reads the outbox table.
 *
 * <p>Eligibility (CA-10): the worker applies framework-level loop protection —
 * {@code origin != target()} — before consulting {@code shouldPropagate}; adding a new
 * external system is a new {@code @Component}, this class never changes. Eligible
 * propagators for one event run concurrently on virtual threads (CA-13); a propagator
 * failure never cancels its siblings — the drain waits for all of them, then leaves the
 * event unprocessed for retry (CA-23), so propagators must be idempotent (at-least-once).
 *
 * <p>Ordering (CA-30): draining is serialized with a lock because both the backup scheduler
 * poll and the LISTEN/NOTIFY trigger call {@link #drainBatch()}; without it two concurrent
 * drains could claim different events of the same entity ({@code SKIP LOCKED} hands out
 * disjoint batches) and propagate them in parallel. Within a batch events process in
 * {@code occurred_at} order, so per-entity order is preserved end to end — concurrency
 * exists only across propagators of one event, never across events of one entity.
 *
 * <p>The claim transaction ({@code FOR UPDATE SKIP LOCKED}) holds until commit. Propagators
 * run outside it, on their own transactions: a publish or propagation failure is logged and
 * skipped (the row stays {@code processed = false}) without rolling back siblings already
 * published in the same batch.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository repository;
    private final IEventPublisher publisher;
    private final List<IEventPropagator> propagators;
    private final OutboxProperties properties;
    private final AsyncTaskExecutor propagationExecutor;
    private final ReentrantLock drainLock = new ReentrantLock();

    public OutboxWorker(
        OutboxRepository repository,
        IEventPublisher publisher,
        List<IEventPropagator> propagators,
        OutboxProperties properties,
        @Qualifier("outboxPropagationExecutor") AsyncTaskExecutor propagationExecutor
    ) {
        this.repository = repository;
        this.publisher = publisher;
        this.propagators = propagators;
        this.properties = properties;
        this.propagationExecutor = propagationExecutor;
    }

    /**
     * Claims and publishes one batch of unprocessed events. Serialized: concurrent callers
     * (scheduler poll vs LISTEN/NOTIFY) queue behind the in-flight drain (CA-30).
     *
     * @return the number of events successfully published in this batch
     */
    @Transactional
    public int drainBatch() {
        drainLock.lock();
        try {
            return drainLocked();
        } finally {
            drainLock.unlock();
        }
    }

    private int drainLocked() {
        List<OutboxEvent> batch = repository.lockUnprocessedBatch(properties.getBatchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                propagateConcurrently(event);
                repository.markProcessed(event.id());
                published++;
            } catch (RuntimeException ex) {
                log.error("Failed to publish outbox event {}; leaving unprocessed for retry", event.id(), ex);
            }
        }
        if (published > 0) {
            log.debug("Outbox drain published {}/{} events", published, batch.size());
        }
        return published;
    }

    /**
     * Runs every eligible propagator for one event in parallel on virtual threads and waits
     * for all of them. {@code allOf} completes only after every future settles, so a failing
     * propagator never cancels its siblings (CA-23); the first failure is then rethrown to
     * leave the event unprocessed for retry.
     */
    private void propagateConcurrently(OutboxEvent event) {
        ExternalSystem origin = ExternalSystem.from(event.sourceSystem());
        SyncedEntityType entityType = SyncedEntityType.fromAggregateType(event.aggregateType());
        List<IEventPropagator> eligible = propagators.stream()
            .filter(propagator -> origin != propagator.target())
            .filter(propagator -> propagator.shouldPropagate(origin, entityType))
            .toList();
        if (eligible.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = eligible.stream()
            .map(propagator -> CompletableFuture.runAsync(() -> propagator.propagate(event), propagationExecutor))
            .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : ex;
        }
    }

    /**
     * Deletes processed events older than the configured retention window.
     *
     * @return the number of rows purged
     */
    @Transactional
    public int purgeExpired() {
        int deleted = repository.purgeProcessedOlderThan(properties.getRetentionDays());
        if (deleted > 0) {
            log.info("Purged {} processed outbox events older than {} days", deleted, properties.getRetentionDays());
        }
        return deleted;
    }
}
