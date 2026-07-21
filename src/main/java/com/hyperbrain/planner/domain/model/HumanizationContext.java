package com.hyperbrain.planner.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The day-specific context the {@code AgendaHumanizer} needs to post-process a raw agenda (H1,
 * HU-01c): the planning-window bounds that set the occupancy denominator, and the priority of each
 * executable so the occupancy trim drops the least valuable work first. Kept separate from
 * {@link HumanizationSettings} (the calibration) so the humanizer stays a pure function of settings +
 * context.
 *
 * @param windowStart          the planning-window start; never null
 * @param windowEnd            the planning-window end; never null, after {@code windowStart}
 * @param priorityByExecutable the ranking score of each executable, used to choose the lowest-value
 *                             block when trimming to the occupancy cap; never null (missing entries
 *                             are treated as the neutral floor 0.0)
 */
public record HumanizationContext(
    OffsetDateTime windowStart,
    OffsetDateTime windowEnd,
    Map<UUID, Double> priorityByExecutable
) {

    public HumanizationContext {
        if (windowStart == null || windowEnd == null) {
            throw new IllegalArgumentException("window bounds must not be null");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException(
                "windowEnd must be after windowStart: " + windowStart + " .. " + windowEnd);
        }
        priorityByExecutable = priorityByExecutable == null ? Map.of() : Map.copyOf(priorityByExecutable);
    }

    /** @return the planning-window span in minutes — the occupancy denominator */
    public long windowMinutes() {
        return Duration.between(windowStart, windowEnd).toMinutes();
    }

    /**
     * @param executableId the executable to look up; may be null
     * @return the executable's ranking score, or the neutral floor {@code 0.0} when unknown
     */
    public double priorityOf(UUID executableId) {
        Double score = priorityByExecutable.get(executableId);
        return score == null ? 0.0 : score;
    }
}
