package com.hyperbrain.planner.domain.model;

import java.util.List;

/**
 * Everything the {@code SleepFrontierCalculator} needs to derive the day's planning window, gathered
 * by the read port so the pure calculator stays free of persistence and of the clock:
 *
 * <ul>
 *   <li>{@code wakeSamples} / {@code bedtimeSamples} — the local wake and bedtime times of day over
 *       the history window, already filtered by the freshness guard (records older than the guard
 *       are dropped upstream, so a stale night never poses as last night's frontier);</li>
 *   <li>{@code fallbackWindow} — the {@code planner_constraints.sleep_window} cold-start window used
 *       when fewer than {@code minSamples} usable nights exist.</li>
 * </ul>
 *
 * <p>The calculator does not read wall-clock instants: the adapter converts each record's local wake
 * and bedtime into a {@link LocalTimeOfDay} in the user's timezone before handing them over, keeping
 * the circular median a pure computation.
 *
 * @param wakeSamples    observed wake times of day (fresh window); never null, may be empty
 * @param bedtimeSamples observed bedtimes of day (fresh window); never null, may be empty
 * @param fallbackWindow the cold-start window from settings; never null
 */
public record SleepFrontierInputs(
    List<LocalTimeOfDay> wakeSamples,
    List<LocalTimeOfDay> bedtimeSamples,
    SleepWindow fallbackWindow
) {

    public SleepFrontierInputs {
        wakeSamples = wakeSamples == null ? List.of() : List.copyOf(wakeSamples);
        bedtimeSamples = bedtimeSamples == null ? List.of() : List.copyOf(bedtimeSamples);
        if (fallbackWindow == null) {
            throw new IllegalArgumentException("fallbackWindow must not be null");
        }
    }
}
