package com.hyperbrain.sync.domain.port.in;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;

/**
 * Inbound port: business handler for a single {@link EntityType} of {@link SentinelEvent}.
 *
 * <p>Implementations are registered by the {@code EventRouter}, keyed by {@link #supportedType()}.
 * Adding support for a new entity type is a matter of adding a new implementation — no change to
 * the router or the consumer (Open/Closed).
 *
 * <p>Design note: this port is intentionally non-generic. There is a single {@code SentinelEvent}
 * type (its polymorphism lives in the raw JSON payload), so a {@code <T extends SentinelEvent>}
 * parameter would be premature generics with no call site that benefits from it (YAGNI).
 */
public interface IEventHandler {

    /**
     * @return the entity type this handler processes; used as the routing key
     */
    EntityType supportedType();

    /**
     * Processes one synchronization event. Runs inside the ingestion transaction, so throwing
     * rolls back the consumer-side dedup insert and lets SQS redeliver (and eventually DLQ).
     *
     * @param event the deserialized, deduplicated event to handle
     */
    void handle(SentinelEvent event);
}
