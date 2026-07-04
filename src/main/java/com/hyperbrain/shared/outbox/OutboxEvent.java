package com.hyperbrain.shared.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single row of the Transactional Outbox ({@code outbox_events}). Written atomically with the
 * domain change that produced it; drained and published by the {@link OutboxWorker}.
 *
 * @param id            row identity
 * @param aggregateType aggregate that emitted the event; drives outbound queue routing
 * @param aggregateId   identity of that aggregate
 * @param eventType     domain event name (past participle, e.g. {@code TaskCompletedEvent})
 * @param payload       serialized event body as raw JSON
 * @param sourceSystem  origin marker for loop protection (RF-17); may be {@code null}
 * @param occurredAt    when the event was recorded
 */
public record OutboxEvent(
    UUID id,
    String aggregateType,
    String aggregateId,
    String eventType,
    String payload,
    String sourceSystem,
    OffsetDateTime occurredAt
) {
}
