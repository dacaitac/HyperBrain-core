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
 * <p>Meal walls are never materialized as {@link AgendaBlock}s and are never written back to Apple
 * (ADR-012/019): they exist only as planning/validation walls.
 *
 * @param label the human-readable meal name surfaced in legibility (e.g. "lunch"); never blank
 * @param start the meal window start, local wall-clock time; never null
 * @param end   the meal window end, local wall-clock time; never null, strictly after {@code start}
 */
public record MealWindow(String label, LocalTime start, LocalTime end) {

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
    }

    /**
     * Resolves this local-time window to a concrete occupied wall on a given day in the user's zone.
     *
     * @param day  the target calendar day; never null
     * @param zone the user's timezone; never null
     * @return the meal window as an {@link OccupiedInterval} wall (no executable, not a read-only AGENDA)
     */
    public OccupiedInterval toWall(LocalDate day, ZoneId zone) {
        if (day == null || zone == null) {
            throw new IllegalArgumentException("day and zone must not be null");
        }
        OffsetDateTime wallStart = day.atTime(start).atZone(zone).toOffsetDateTime();
        OffsetDateTime wallEnd = day.atTime(end).atZone(zone).toOffsetDateTime();
        return new OccupiedInterval(null, wallStart, wallEnd, false);
    }
}
