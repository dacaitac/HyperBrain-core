package com.hyperbrain.core.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The scheduling authority a contained executable copies from (ADR-039 hard-copy rule): the
 * window and cycle of its container — the {@code TIME_BLOCK} it is a member of
 * ({@code container_block_id}, which wins) or its parent ({@code parent_id}).
 *
 * @param containerId   the authoritative container's {@code core_executable.id}
 * @param containerName the container's display name (for the visible Sync Note)
 * @param startTime     the container's start instant; may be null (an undated parent imposes
 *                      no schedule)
 * @param endTime       the container's end instant; may be null
 * @param cycleId       the container's cycle; null carries no signal (child keeps its own)
 */
public record ContainerSchedule(
    UUID containerId,
    String containerName,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    UUID cycleId
) {

    /** @return true when the container actually imposes a schedule (it has a start instant) */
    public boolean imposesSchedule() {
        return startTime != null;
    }
}
