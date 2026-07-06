package com.hyperbrain.shared.messaging;

import com.hyperbrain.shared.outbox.OutboxEvent;

/**
 * Extension point of the outbox drain: feature modules implement it to propagate a drained
 * event to an external satellite (e.g. the sync module's Apple write-back, HU-09c). The
 * {@code OutboxWorker} invokes every registered propagator after the primary publish; a
 * propagator not interested in an event must return without side effects.
 *
 * <p>Implementations run inside the drain transaction. Throwing a {@link RuntimeException}
 * leaves the outbox event unprocessed so the whole publish is retried on the next poll —
 * downstream consumers must therefore tolerate at-least-once delivery.
 */
public interface IEventPropagator {

    /**
     * Propagates one drained outbox event, or does nothing if the event is not of interest.
     *
     * @param event the event being drained
     */
    void propagate(OutboxEvent event);
}
