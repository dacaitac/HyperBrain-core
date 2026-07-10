package com.hyperbrain.planner.domain.model;

/**
 * The two edges of the day's planning window as local times of day: the estimated wake time (lower
 * edge) and the estimated bedtime (upper edge). Outside {@code [wake, bedtime]} is the hard sleep
 * frontier — never assignable space (ADR-013 D2). Because the edges are wall-clock times, the
 * window can cross midnight (a bedtime past 00:00), which the concrete-day resolution handles.
 *
 * <p>{@code observed} distinguishes a window derived from the circular median of real
 * {@code tel_sleep_record} history from the {@code planner_constraints.sleep_window} cold-start
 * fallback, so the agenda can surface which frontier it planned against.
 *
 * @param wakeEstimate    the lower edge — estimated wake time of day; never null
 * @param bedtimeEstimate the upper edge — estimated bedtime time of day; never null
 * @param observed        true when derived from observed sleep history, false for the cold-start
 *                        fallback
 */
public record SleepWindow(
    LocalTimeOfDay wakeEstimate,
    LocalTimeOfDay bedtimeEstimate,
    boolean observed
) {

    public SleepWindow {
        if (wakeEstimate == null) {
            throw new IllegalArgumentException("wakeEstimate must not be null");
        }
        if (bedtimeEstimate == null) {
            throw new IllegalArgumentException("bedtimeEstimate must not be null");
        }
    }

    /**
     * Builds an observed sleep window from the circular-median edges.
     *
     * @param wakeEstimate    the estimated wake time of day
     * @param bedtimeEstimate the estimated bedtime time of day
     * @return an observed window
     */
    public static SleepWindow observed(LocalTimeOfDay wakeEstimate, LocalTimeOfDay bedtimeEstimate) {
        return new SleepWindow(wakeEstimate, bedtimeEstimate, true);
    }

    /**
     * Builds a cold-start fallback window from {@code planner_constraints.sleep_window}.
     *
     * @param wakeEstimate    the configured wake time of day
     * @param bedtimeEstimate the configured bedtime time of day
     * @return a fallback window
     */
    public static SleepWindow fallback(LocalTimeOfDay wakeEstimate, LocalTimeOfDay bedtimeEstimate) {
        return new SleepWindow(wakeEstimate, bedtimeEstimate, false);
    }
}
