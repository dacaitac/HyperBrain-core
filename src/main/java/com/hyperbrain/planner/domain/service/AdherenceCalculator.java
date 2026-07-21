package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.AdherenceThresholds;
import com.hyperbrain.planner.domain.model.DailyAdherenceReport;
import com.hyperbrain.planner.domain.model.DailyBlockObservation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Computes the H0 daily rollup (#17) from a day's block observations and behavioral signals. Pure
 * and framework-free (wired as a bean in {@code PlannerConfig}): a deterministic function of its
 * inputs, so the formula is unit-tested in isolation.
 *
 * <p>Adherence is block-level: the fraction of planner blocks executed past the temporal tolerance.
 * The lead measures ride on the same execution signal — {@code wigHit} is the reserved WIG block
 * having been executed, and {@code abandoned} distinguishes a day let go (low adherence, no replan)
 * from one actively re-adjusted (a replan happened).
 */
public class AdherenceCalculator {

    private final AdherenceThresholds thresholds;

    public AdherenceCalculator(AdherenceThresholds thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * Rolls up one local day.
     *
     * @param date            the local day covered; never null
     * @param zone            the user's timezone the day is reasoned in; never null
     * @param blocks          the day's planner blocks; never null (empty when nothing was planned)
     * @param replanCount     the count of {@code REPLAN_AGENDA} commands issued that day; &ge; 0
     * @param ritualCompleted the ADR-018 ritual proxy for the day, or null when deferred/unknown
     * @return the computed rollup
     */
    public DailyAdherenceReport compute(
        LocalDate date,
        ZoneId zone,
        List<DailyBlockObservation> blocks,
        int replanCount,
        Boolean ritualCompleted
    ) {
        int tolerance = thresholds.executedMinMinutes();
        int planned = blocks.size();
        int executed = (int) blocks.stream().filter(b -> b.executed(tolerance)).count();
        double adherence = planned == 0 ? 0.0 : (double) executed / planned;
        boolean wigHit = blocks.stream().anyMatch(b -> b.wig() && b.executed(tolerance));
        boolean abandoned = planned > 0
            && replanCount == 0
            && adherence < thresholds.abandonmentAdherenceThreshold();
        return new DailyAdherenceReport(
            date, zone, planned, executed, adherence, wigHit, ritualCompleted, replanCount, abandoned);
    }
}
