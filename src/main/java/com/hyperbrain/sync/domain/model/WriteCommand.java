package com.hyperbrain.sync.domain.model;

import java.util.UUID;

/**
 * Command ordering SentinelAPI to create/update/delete an EventKit entity, published to
 * {@code apple-commands.fifo} (HU-09c, TD-03). Correlated with its {@link WriteCommandResult}
 * by {@code commandId} (ADR-010).
 *
 * @param commandId   correlation id; deterministic per outbox event so retries stay idempotent
 * @param commandType payload discriminator ({@code REMINDER} / {@code CALENDAR_EVENT})
 * @param operation   requested operation
 * @param entityId    EventKit identifier; required for UPDATED/DELETED, {@code null} for CREATED
 * @param payload     entity payload; {@code null} for DELETED
 */
public record WriteCommand(
    UUID commandId,
    CommandType commandType,
    Operation operation,
    String entityId,
    WritePayload payload
) {
}
