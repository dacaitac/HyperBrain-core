package com.hyperbrain.core.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The schedule a contained executable must carry, as {@link ContainmentPolicy#assertedSchedule}
 * derives it from the container (DR-10, the hard copy). These three values are SYSTEM-owned for as
 * long as the containment stands: an inbound human edit that moves them is re-asserted.
 *
 * @param startTime the container's start instant; may be null when the container imposes no schedule
 * @param endTime   the end instant the child may carry — null for the reminder-backed types (DR-01)
 * @param cycleId   the cycle the child must carry: the container's, or its own when the container
 *                  has none
 */
public record ContainedSchedule(OffsetDateTime startTime, OffsetDateTime endTime, UUID cycleId) {
}
