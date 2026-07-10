package com.hyperbrain.planner.domain.model;

/**
 * A wall-clock time of day expressed as minutes since local midnight, in {@code [0, 1440)}. The
 * sleep frontier works in this space so the circular median can wrap the midnight seam correctly:
 * 23:50 and 00:10 average to ~00:00, never to noon.
 *
 * @param minutesOfDay minutes since local midnight; {@code 0 ≤ v < 1440}
 */
public record LocalTimeOfDay(int minutesOfDay) {

    /** Minutes in a full day; the modulus of the circular clock. */
    public static final int MINUTES_PER_DAY = 24 * 60;

    public LocalTimeOfDay {
        if (minutesOfDay < 0 || minutesOfDay >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException(
                "minutesOfDay must be in [0, " + MINUTES_PER_DAY + "): " + minutesOfDay);
        }
    }

    /**
     * Builds a time of day from an hour/minute pair.
     *
     * @param hour   the hour, {@code 0–23}
     * @param minute the minute, {@code 0–59}
     * @return the corresponding time of day
     */
    public static LocalTimeOfDay of(int hour, int minute) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be in [0, 23]: " + hour);
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("minute must be in [0, 59]: " + minute);
        }
        return new LocalTimeOfDay(hour * 60 + minute);
    }
}
