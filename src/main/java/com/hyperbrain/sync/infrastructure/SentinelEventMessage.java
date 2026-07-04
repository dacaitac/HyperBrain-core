package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;

import java.time.OffsetDateTime;

/**
 * Wire DTO for the SentinelAPI → SQS message (snake_case JSON). Kept in the infrastructure layer
 * so the Jackson coupling never reaches the domain: it maps to {@link SentinelEvent} via
 * {@link #toDomain()}, flattening the raw {@code payload} node to a JSON string.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record SentinelEventMessage(
    String schemaVersion,
    String eventId,
    String sourceSystem,
    EntityType entityType,
    String entityId,
    Operation operation,
    OffsetDateTime occurredAt,
    JsonNode payload
) {

    /**
     * @return the domain event; {@code payload} is serialized back to raw JSON text, or
     *         {@code null} when absent
     */
    SentinelEvent toDomain() {
        String rawPayload = (payload == null || payload.isNull()) ? null : payload.toString();
        return new SentinelEvent(
            schemaVersion, eventId, sourceSystem, entityType, entityId, operation, occurredAt, rawPayload);
    }
}
