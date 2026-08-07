package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;

/**
 * The stretch of the day a block may be retimed within — the bound of the intelligent layer's
 * arrangement authority (Daniel, 2026-08-07).
 *
 * <p><b>Why it exists.</b> The template is the shape of the day (ADR-040 D2), and a block born in a
 * band belongs to that band: «Casa» is the evening, «Meta de la mañana» is the morning. The LLM owns
 * the arrangement — the order, the hour, the grouping, the naming — but an arrangement that takes a
 * block out of its band does not rearrange the day, it dissolves its form. In production the model
 * moved the household block to seven in the morning and the band travelled with it, so the day came
 * out named after a shape it no longer had. Tolerance is zero by decision: a block whose band the
 * proposal leaves is a wall breach, and the day degrades to the deterministic floor.
 *
 * <p><b>Where a band comes from.</b> Two sources, one rule. An ordinary band is the template slot
 * resolved onto the day (displaced by the real wake, clamped to the planning bounds). A <b>meal</b>
 * band is that same band widened to the meal's plausible hours — breakfast in the morning, lunch
 * around midday, dinner in the evening — because a meal legitimately floats within a sensible stretch
 * while nothing makes breakfast at three in the afternoon sensible. The width is configuration
 * ({@link MealWindow}); the rule that a block never leaves its band is not.
 *
 * <p>A band is a <b>bound on movement, never a permission to overlap</b>: the sleep frontier, the
 * read-only AGENDA windows and the blocks somebody already owns keep walling independently, so being
 * inside the band says nothing about whether the time is free.
 *
 * @param label the band's human-readable name, as the day reads it (e.g. «Casa»); never blank
 * @param start the band's lower edge; never null
 * @param end   the band's upper edge; never null, strictly after {@code start}
 */
public record RetimingBand(String label, OffsetDateTime start, OffsetDateTime end) {

    public RetimingBand {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("band label must not be blank");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("band edges must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("band end must be after start: " + start + " .. " + end);
        }
        label = label.strip();
    }

    /**
     * Whether a proposed window lies entirely inside this band. Edges count as inside: a block that
     * runs to the very end of its band has not left it.
     *
     * @param from the proposed window start; never null
     * @param to   the proposed window end; never null
     * @return true when {@code [from, to]} is contained in the band
     */
    public boolean contains(OffsetDateTime from, OffsetDateTime to) {
        return !from.isBefore(start) && !to.isAfter(end);
    }

    /**
     * This band widened to also cover {@code [from, to]} — how a meal band absorbs its plausible hours
     * without ever narrowing the band the floor already laid (a narrowing could leave the floor's own
     * block outside its band, degrading every day for a placement the model never made).
     *
     * @param from the lower edge to absorb; never null
     * @param to   the upper edge to absorb; never null
     * @return the widened band; never null, never narrower than this one
     */
    public RetimingBand spanning(OffsetDateTime from, OffsetDateTime to) {
        return new RetimingBand(label,
            from.isBefore(start) ? from : start,
            to.isAfter(end) ? to : end);
    }

    /**
     * This band narrowed to the run's planning bounds, so a band can never authorize a move into the
     * hours the run is not planning (the past, on a replan) nor past bedtime.
     *
     * @param lowerBound the earliest instant this run may place at; never null
     * @param upperBound the bedtime edge; never null
     * @return the clamped band, or null when nothing of the band lies inside the bounds
     */
    public RetimingBand clampedTo(OffsetDateTime lowerBound, OffsetDateTime upperBound) {
        OffsetDateTime clampedStart = start.isBefore(lowerBound) ? lowerBound : start;
        OffsetDateTime clampedEnd = end.isAfter(upperBound) ? upperBound : end;
        return clampedEnd.isAfter(clampedStart)
            ? new RetimingBand(label, clampedStart, clampedEnd)
            : null;
    }
}
