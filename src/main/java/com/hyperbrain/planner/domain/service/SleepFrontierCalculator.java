package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.model.SleepWindow;

import java.util.List;

/**
 * Pure domain service that derives the day's planning window — the two edges {@code wake_estimate}
 * and {@code bedtime_estimate} — as the <b>circular median</b> of the observed wake/bedtime times of
 * day over the history window (planner engine doc, ADR-013 D2). Analogous to the
 * {@code LearnedUnitCostCalculator}: a fixed-formula, single-algorithm service.
 *
 * <p><b>Why circular.</b> Wall-clock times live on a 24h ring. A linear median of 23:50 and 00:10
 * would return ~12:00; the correct answer is ~00:00. This service works modulo
 * {@link LocalTimeOfDay#MINUTES_PER_DAY}: it anchors on the first sample, unwraps every other sample
 * to the nearest lift of that anchor (within ±12h), takes the ordinary median of the unwrapped
 * values, then wraps the result back onto the ring. Anchoring on a real sample is exact for the
 * clustered sleep/wake times the frontier sees (a person does not wake at random around the clock).
 *
 * <p><b>Cold-start.</b> Below {@link PlannerConstraints#minSleepSamples()} usable nights for an edge,
 * that edge falls back to {@code planner_constraints.sleep_window}. The freshness guard (records
 * older than {@link PlannerConstraints#sleepFreshnessHours()}) is applied upstream by the read port,
 * so a stale history arrives here already empty and falls back cleanly.
 */
public class SleepFrontierCalculator {

    private static final int HALF_DAY = LocalTimeOfDay.MINUTES_PER_DAY / 2;

    private final PlannerConstraints constraints;

    /** Creates a calculator using the sanctioned default constraints. */
    public SleepFrontierCalculator() {
        this(PlannerConstraints.DEFAULT);
    }

    /**
     * Creates a calculator with explicit constraints (calibration seam).
     *
     * @param constraints the planner constraints; never null
     */
    public SleepFrontierCalculator(PlannerConstraints constraints) {
        if (constraints == null) {
            throw new IllegalArgumentException("constraints must not be null");
        }
        this.constraints = constraints;
    }

    /**
     * Derives the planning window from the observed sleep history, falling back to the cold-start
     * window per edge when the samples are too few.
     *
     * @param inputs the fresh wake/bedtime samples and the cold-start fallback window; never null
     * @return the resolved window; {@code observed} only when both edges came from real samples
     */
    public SleepWindow computeWindow(SleepFrontierInputs inputs) {
        LocalTimeOfDay fallbackWake = inputs.fallbackWindow().wakeEstimate();
        LocalTimeOfDay fallbackBedtime = inputs.fallbackWindow().bedtimeEstimate();

        LocalTimeOfDay wake = circularMedianOrNull(inputs.wakeSamples());
        LocalTimeOfDay bedtime = circularMedianOrNull(inputs.bedtimeSamples());

        boolean observed = wake != null && bedtime != null;
        return new SleepWindow(
            wake != null ? wake : fallbackWake,
            bedtime != null ? bedtime : fallbackBedtime,
            observed);
    }

    /**
     * The circular median of one edge's samples, or null when fewer than the required minimum.
     *
     * @param samples the edge's times of day
     * @return the circular median, or null to signal cold-start fallback for this edge
     */
    private LocalTimeOfDay circularMedianOrNull(List<LocalTimeOfDay> samples) {
        if (samples.size() < constraints.minSleepSamples()) {
            return null;
        }
        return circularMedian(samples);
    }

    /**
     * Computes the circular median of a non-empty sample set on the 24h ring.
     *
     * @param samples the times of day; non-empty
     * @return the median time of day, wrapped back onto {@code [0, 1440)}
     */
    private static LocalTimeOfDay circularMedian(List<LocalTimeOfDay> samples) {
        int anchor = samples.get(0).minutesOfDay();
        int[] unwrapped = samples.stream()
            .mapToInt(sample -> unwrapNear(sample.minutesOfDay(), anchor))
            .sorted()
            .toArray();

        double median = medianOf(unwrapped);
        int wrapped = Math.floorMod((int) Math.round(median), LocalTimeOfDay.MINUTES_PER_DAY);
        return new LocalTimeOfDay(wrapped);
    }

    /**
     * Lifts a time of day to the representative within ±12h of the anchor, so samples straddling the
     * midnight seam sort together instead of splitting across the ring.
     *
     * @param value  the sample minutes since midnight
     * @param anchor the anchor minutes since midnight
     * @return {@code value} shifted by a whole day when that brings it within ±12h of {@code anchor}
     */
    private static int unwrapNear(int value, int anchor) {
        int diff = value - anchor;
        if (diff > HALF_DAY) {
            return value - LocalTimeOfDay.MINUTES_PER_DAY;
        }
        if (diff < -HALF_DAY) {
            return value + LocalTimeOfDay.MINUTES_PER_DAY;
        }
        return value;
    }

    /**
     * The ordinary median of a sorted int array (average of the two middle values on even length).
     *
     * @param sorted the ascending values; non-empty
     * @return the median
     */
    private static double medianOf(int[] sorted) {
        int n = sorted.length;
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
