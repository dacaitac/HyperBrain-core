package com.hyperbrain.shared.messaging;

import com.hyperbrain.shared.outbox.OutboxEvent;

/*
 * Design pattern: Strategy (registry of propagation strategies discovered by Spring)
 * Reason: the OutboxWorker iterates every registered propagator, so adding a new external
 * system is a new @Component implementing this interface — the drain never changes (OCP,
 * HU-14 CA-10).
 */

/**
 * Extension point of the outbox drain: feature modules implement it to propagate a drained
 * event to an external satellite (Apple write-back HU-09c, Notion write-back HU-10). The
 * {@code OutboxWorker} applies framework-level loop protection ({@code origin != target()},
 * RF-17) before consulting {@link #shouldPropagate}; eligible propagators for one event run
 * concurrently on virtual threads (HU-14 CA-13).
 *
 * <p>Concurrency contract: {@link #propagate} executes on a virtual thread outside the drain
 * transaction, so implementations must be idempotent — a failing propagator leaves the outbox
 * event unprocessed and the whole publish is retried on the next poll (at-least-once), and
 * any state written before the failure (error markers, command logs) survives the rethrow.
 * Failures are independent: throwing never cancels the other propagators of the same event
 * (CA-23).
 */
public interface IEventPropagator {

    /**
     * @return the external system this propagator writes to; the drain never invokes it for
     *         events whose origin equals this target (framework loop protection)
     */
    ExternalSystem target();

    /**
     * Decides whether an event with the given origin and entity classification is of interest.
     * Called after the framework loop-protection check, on the drain thread — must be cheap
     * and side-effect free.
     *
     * @param origin     origin system of the drained event (never equal to {@link #target()})
     * @param entityType coarse classification of the aggregate the event describes
     * @return true if {@link #propagate} must be invoked for the event
     */
    boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType);

    /**
     * Propagates one drained outbox event. Runs on a virtual thread, concurrently with the
     * other eligible propagators of the same event; never invoked twice concurrently for the
     * same entity (the drain is serialized and processes events in order, HU-14 CA-30).
     *
     * @param event the event being drained
     */
    void propagate(OutboxEvent event);
}
