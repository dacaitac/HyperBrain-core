package com.hyperbrain.core.application.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code SubtaskCompletedEvent} (events-v1.yaml v1.5.0, DR-07).
 *
 * @param subtaskId          the completed user subtask
 * @param parentId           its parent executable
 * @param completedAt        observed completion instant
 * @param imputedTimeBlockId open block of the parent covering the completion; null means
 *                           unplanned work
 * @param parentProgress     recomputed materialized progress of the parent; null when the
 *                           parent has no user subtasks
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubtaskCompletedPayload(
    UUID subtaskId,
    UUID parentId,
    OffsetDateTime completedAt,
    UUID imputedTimeBlockId,
    Double parentProgress
) {
}
