package com.hyperbrain.core.application.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code SubtaskCompletedEvent} (DR-07).
 *
 * <p>The block a completed subtask was imputed to is no longer carried: the imputation went with the
 * focus register (ADR-040 D13), which existed to say how much of the work done had been planned — a
 * question that lost its consumer along with the rest of that series. Removing the field rather than
 * leaving it forever null is deliberate: a field that can only be null is a lie about what the event
 * knows.
 *
 * @param subtaskId          the completed user subtask
 * @param parentId           its parent executable
 * @param completedAt        observed completion instant
 * @param parentProgress     recomputed materialized progress of the parent; null when the
 *                           parent has no user subtasks
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubtaskCompletedPayload(
    UUID subtaskId,
    UUID parentId,
    OffsetDateTime completedAt,
    Double parentProgress
) {
}
