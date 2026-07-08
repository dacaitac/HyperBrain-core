package com.hyperbrain.core.application.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code FocusSwitchedEvent} (events-v1.yaml v1.5.0, ADR-013 D5). Not mirrored to
 * satellites; the snapshot subtask travels through its own {@code ExecutableCreatedEvent}.
 *
 * @param userId               owning user
 * @param previousExecutableId the cut task (stays IN_PROGRESS, pending re-estimation)
 * @param newExecutableId      the task that took the focus
 * @param snapshotSubtaskId    system-generated snapshot created under the cut task
 * @param settledBlockId       the cut task's block settled as SETTLED; null when it had none
 * @param switchedAt           the cut instant
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FocusSwitchedPayload(
    UUID userId,
    UUID previousExecutableId,
    UUID newExecutableId,
    UUID snapshotSubtaskId,
    UUID settledBlockId,
    OffsetDateTime switchedAt
) {
}
