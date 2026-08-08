package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * A protected meal anchor the humanized floor (H1, HU-01c) never fills with work: a local-time window
 * (e.g. lunch 12:30–13:30) the Planner treats as a hard wall so the day keeps its human rhythm. The
 * window is stored in wall-clock local time and resolved against the target day and the user's zone,
 * so it lands at the same civil time every day regardless of DST — meals are a daily human anchor, not
 * an instant.
 *
 * <p><b>The anchor floats inside a plausible band</b> (Daniel, 2026-08-07). A meal is not a fixed
 * appointment: lunch may slide an hour when the day demands it, and that is a normal day. What is not
 * a normal day is breakfast at three in the afternoon, which is what production produced. So the
 * anchor carries a second, wider pair of hours — {@code bandStart}/{@code bandEnd} — that says where
 * this meal may plausibly sit at all, and the band of the day that holds the meal is widened to it
 * ({@link RetimingBand}). Both pairs are configuration, editable without a deploy, like the template's
 * labels; the rule that a meal never leaves its plausible band is not.
 *
 * <p>Meal walls are never materialized as {@link AgendaBlock}s and are never written back to Apple
 * (ADR-012/019): they exist only as planning/validation walls.
 *
 * @param label     the human-readable meal name surfaced in legibility (e.g. "lunch"); never blank
 * @param start     the meal window start, local wall-clock time; never null
 * @param end       the meal window end, local wall-clock time; never null, strictly after {@code start}
 * @param bandStart the earliest plausible hour for this meal; never null, never after {@code start}
 * @param bandEnd   the latest plausible hour for this meal; never null, never before {@code end}
 */
public record MealWindow(String label, LocalTime start, LocalTime end,
                         LocalTime bandStart, LocalTime bandEnd) {

    public MealWindow {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be strictly after start: " + start + " .. " + end);
        }
        if (bandStart == null || bandEnd == null) {
            throw new IllegalArgumentException("band edges must not be null");
        }
        // The band is where the meal may sit, so it must at least contain where the meal normally sits;
        // a band narrower than its own anchor would forbid the very hour it is configured for.
        if (bandStart.isAfter(start) || bandEnd.isBefore(end)) {
            throw new IllegalArgumentException(
                "the plausible band must enclose the meal window: " + bandStart + ".." + bandEnd
                    + " does not enclose " + start + ".." + end);
        }
    }

    /**
     * A rigid meal: its plausible band is its own window, so it may not float at all. The honest
     * default for a meal configured without a band — nothing was said about where else it could sit.
     *
     * @param label the meal name; never blank
     * @param start the meal window start; never null
     * @param end   the meal window end; never null, strictly after {@code start}
     */
    public MealWindow(String label, LocalTime start, LocalTime end) {
        this(label, start, end, start, end);
    }

    /**
     * Resolves this local-time window to a concrete occupied wall on a given day in the user's zone.
     *
     * @param day  the target calendar day; never null
     * @param zone the user's timezone; never null
     * @return the meal window as an {@link OccupiedInterval} wall (no executable, not a read-only AGENDA)
     */
    public OccupiedInterval toWall(LocalDate day, ZoneId zone) {
        requireDayAndZone(day, zone);
        return new OccupiedInterval(null, at(start, day, zone), at(end, day, zone), false);
    }

    /**
     * Resolves the plausible band to concrete instants: the stretch of the day this meal may occupy,
     * and therefore the width the band of the day holding it is widened to.
     *
     * @param day  the target calendar day; never null
     * @param zone the user's timezone; never null
     * @return the plausible band as a concrete {@link RetimingBand}; never null
     */
    public RetimingBand toBand(LocalDate day, ZoneId zone) {
        requireDayAndZone(day, zone);
        return new RetimingBand(label, at(bandStart, day, zone), at(bandEnd, day, zone));
    }

    private static void requireDayAndZone(LocalDate day, ZoneId zone) {
        if (day == null || zone == null) {
            throw new IllegalArgumentException("day and zone must not be null");
        }
    }

    private static OffsetDateTime at(LocalTime time, LocalDate day, ZoneId zone) {
        return day.atTime(time).atZone(zone).toOffsetDateTime();
    }
}
