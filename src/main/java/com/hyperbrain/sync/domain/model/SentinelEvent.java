package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;

/**
 * Inbound synchronization event produced by an external system (EventSentinelAPI) and
 * consumed from {@code sync-events.fifo}. This is the domain-level representation of the
 * SentinelAPI → SQS contract v1 (HU-09).
 *
 * <p>The {@code payload} is kept as a raw JSON string on purpose: the pipeline skeleton
 * does not interpret it. Each {@code IEventHandler} is responsible for parsing the payload
 * that corresponds to its {@link EntityType}. This keeps the domain free of any JSON library.
 *
 * @param schemaVersion contract version (currently {@code "1"})
 * @param eventId       producer-assigned unique id; also the consumer-side dedup key
 * @param sourceSystem  originating system (e.g. {@code "APPLE"}); drives loop protection
 * @param entityType    kind of external entity
 * @param entityId      external identifier (EventKit id); maps to {@code sync_mappings.external_id}
 * @param operation     lifecycle operation
 * @param occurredAt    when the change happened at the source
 * @param payload       entity-specific body as raw JSON (never interpreted by the pipeline)
 */
public record SentinelEvent(
    String schemaVersion,
    String eventId,
    String sourceSystem,
    EntityType entityType,
    String entityId,
    Operation operation,
    OffsetDateTime occurredAt,
    String payload
) {
}
