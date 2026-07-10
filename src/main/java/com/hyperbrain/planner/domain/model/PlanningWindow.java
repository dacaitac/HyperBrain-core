package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;

/**
 * The concrete-day planning window as instants: the sleep frontier {@code [frontierStart, frontierEnd]}
 * resolved to the target day, and the {@code lowerBound} the ranking may start filling from. For a
 * full-day run the lower bound equals the frontier start (wake); for a replan-from-now run it is
 * {@code max(wake, now)} so the past is never re-planned. The frontier edges themselves are the hard
 * wall the {@code AgendaValidator} re-imposes, independent of the lower bound.
 *
 * @param frontierStart the concrete wake edge; never null
 * @param frontierEnd   the concrete bedtime edge; never null, after {@code frontierStart}
 * @param lowerBound    where the ranking starts filling; never null, within the frontier
 */
public record PlanningWindow(
    OffsetDateTime frontierStart,
    OffsetDateTime frontierEnd,
    OffsetDateTime lowerBound
) {

    public PlanningWindow {
        if (frontierStart == null || frontierEnd == null || lowerBound == null) {
            throw new IllegalArgumentException("window instants must not be null");
        }
        if (!frontierEnd.isAfter(frontierStart)) {
            throw new IllegalArgumentException(
                "frontierEnd must be after frontierStart: " + frontierStart + " .. " + frontierEnd);
        }
        if (lowerBound.isBefore(frontierStart) || lowerBound.isAfter(frontierEnd)) {
            throw new IllegalArgumentException("lowerBound must lie within the frontier: " + lowerBound);
        }
    }
}
