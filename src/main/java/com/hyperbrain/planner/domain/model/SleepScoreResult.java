package com.hyperbrain.planner.domain.model;

/**
 * Outcome of the {@code sleep_score} computation (ADR-016 v1.4.0): the per-night score the normalizer
 * writes to {@code tel_sleep_record.sleep_score}, plus the derived metrics and sub-scores that back it
 * (persisted in the {@code stages} JSONB for legibility and later recalibration).
 *
 * <p>The score is the raw per-night value. Downstream smoothing over recent nights is the planner's
 * job, not the normalizer's: {@code EnergyResolver} maps this score onto the F3/F6 tiers.
 *
 * @param score            the per-night sleep score in {@code [0, 100]}
 * @param lowConfidence    true when computed from duration + efficiency only (no stage breakdown)
 * @param tstHours         total sleep time, hours
 * @param efficiency       sleep efficiency TST/TIB, {@code [0, 1]}
 * @param deepFraction     deep sleep as a fraction of TST, {@code [0, 1]}
 * @param remFraction      REM sleep as a fraction of TST, {@code [0, 1]}
 * @param wasoMinutes      wake-after-sleep-onset, minutes
 * @param durationSubScore duration sub-score, {@code [0, 100]}
 * @param efficiencySubScore efficiency sub-score, {@code [0, 100]}
 * @param deepSubScore     deep-sleep sub-score, {@code [0, 100]}; null when no stage breakdown
 * @param remSubScore      REM sub-score, {@code [0, 100]}; null when no stage breakdown
 * @param wasoSubScore     fragmentation sub-score, {@code [0, 100]}; null when no stage breakdown
 */
public record SleepScoreResult(
    int score,
    boolean lowConfidence,
    double tstHours,
    double efficiency,
    double deepFraction,
    double remFraction,
    double wasoMinutes,
    double durationSubScore,
    double efficiencySubScore,
    Double deepSubScore,
    Double remSubScore,
    Double wasoSubScore
) {
}
