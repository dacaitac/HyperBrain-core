package com.hyperbrain.shared.outbox;

import java.util.List;
import java.util.UUID;

/**
 * Data-access port for the Transactional Outbox. The {@link OutboxWorker} is the only component
 * that uses it; no business logic touches {@code outbox_events} directly.
 */
public interface OutboxRepository {

    /**
     * Locks and returns the oldest unprocessed events, up to {@code limit}, using
     * {@code FOR UPDATE SKIP LOCKED} so concurrent workers never claim the same rows.
     * Must be called within an active transaction; the lock is held until it commits.
     *
     * @param limit maximum number of rows to claim
     * @return the locked batch, oldest first (may be empty)
     */
    List<OutboxEvent> lockUnprocessedBatch(int limit);

    /**
     * Marks an event as processed ({@code processed = true, processed_at = now()}).
     *
     * @param id the event id
     */
    void markProcessed(UUID id);

    /**
     * Deletes processed events whose {@code processed_at} is older than {@code days}.
     *
     * @param days retention window in days
     * @return number of rows deleted
     */
    int purgeProcessedOlderThan(int days);
}
