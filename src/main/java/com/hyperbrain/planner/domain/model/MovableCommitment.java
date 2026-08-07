package com.hyperbrain.planner.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An {@code ACTIVITY} or {@code LEARNING_SESSION} standing on the day being planned — the middle tier
 * of the rigidity hierarchy (ADR-040 D9).
 *
 * <p>It is never a member of a window: it carries a calendar window of its own, which is exactly why it
 * cannot be put inside one. What the planner may do is move it <b>in hour, inside its day</b>, for the
 * case Daniel described — something interrupted him right before it was due to start, so it needs
 * re-timing rather than being lost.
 *
 * @param executableId the commitment; never null
 * @param start        its current start; never null
 * @param end          its current end; never null
 */
public record MovableCommitment(UUID executableId, OffsetDateTime start, OffsetDateTime end) {

    public MovableCommitment {
        if (executableId == null || start == null || end == null) {
            throw new IllegalArgumentException("executableId, start and end must not be null");
        }
    }

    /** @return how long the commitment lasts, which is what any new placement must fit */
    public int durationMinutes() {
        return (int) Duration.between(start, end).toMinutes();
    }
}
