package com.hyperbrain.planner.domain.model;

/**
 * The calibrable domain constants of the deterministic agenda floor (#6a), the Planner's counterpart
 * to {@code PriorityWeights}/{@code AlignmentWeights}. Grouped here so a settings adapter can supply
 * an alternative instance (loaded from {@code sys_user.settings.planner_constants}, JSONB) without
 * touching the domain services — no formula constant is hard-coded in a service.
 *
 * <p><b>Sleep frontier.</b> {@code minSleepSamples} nights are required before the circular median is
 * trusted; below that the cold-start fallback window is used. {@code sleepFreshnessHours} bounds how
 * old the most recent record may be before the whole history is treated as absent (a device left
 * unsynced must not freeze an outdated frontier).
 *
 * <p><b>WIG portfolio (F1).</b> {@code wigBlockMinutes} is the size of the LEAD_MEASURE block reserved
 * first for each active MCI that requires one; it is a hard <em>minimum</em> (≥ 45), never trimmed by
 * energy and scalable upward when the day has room. The required-pace metric that orders the portfolio
 * uses {@code pacePrecisionEpsilon} (the {@code ε} that keeps the near-deadline denominator finite)
 * and {@code maxRequiredPace} (the cap that keeps an overdue MCI from producing Infinity).
 *
 * <p><b>Degraded portfolio (F1/F5).</b> When the day's block budget is smaller than the number of
 * active MCIs, {@code hysteresisMargin} keeps yesterday's chosen MCI ahead unless another beats it by
 * that margin (applied only in degraded mode), and {@code degradedStreakThreshold} is the number of
 * consecutive block-less degraded days after which an MCI is force-promoted to break starvation.
 *
 * <p><b>Degraded mode (F5).</b> {@code degradedUrgentCount} is how many top-ranked urgent executables
 * the floor still schedules alongside the WIG portfolio when data is missing or generation partially
 * fails.
 *
 * <p><b>High-load classification.</b> An executable counts against the F6 quota when its
 * {@code energy_drain} is at or above {@code highLoadDrainFloor} on the 1–5 profile scale.
 *
 * @param minSleepSamples        minimum usable nights before the circular median is trusted (default 5)
 * @param sleepHistoryDays       how many days back the frontier samples the sleep history (default 14)
 * @param sleepFreshnessHours    max age of the most recent record before the history is treated as
 *                               absent (default 36)
 * @param wigBlockMinutes        the reserved WIG block minimum size in minutes (F1; default 45, ≥ 45)
 * @param pacePrecisionEpsilon   the {@code ε} guarding the required-pace denominator (F1; default 0.05)
 * @param maxRequiredPace        the cap on required pace, bounding an overdue MCI (F1; default 100.0)
 * @param hysteresisMargin       degraded-mode stickiness margin for yesterday's MCI (F1; default 0.10)
 * @param degradedStreakThreshold consecutive block-less degraded days that force-promote an MCI
 *                               (F1 release valve; default 3)
 * @param degradedUrgentCount    urgent executables scheduled beside the WIGs in degraded mode (default 2)
 * @param highLoadDrainFloor     the {@code energy_drain} at/above which a block counts as high-load
 *                               (1–5 scale; default 4)
 */
public record PlannerConstraints(
    int minSleepSamples,
    int sleepHistoryDays,
    int sleepFreshnessHours,
    int wigBlockMinutes,
    double pacePrecisionEpsilon,
    double maxRequiredPace,
    double hysteresisMargin,
    int degradedStreakThreshold,
    int degradedUrgentCount,
    int highLoadDrainFloor
) {

    /** The minimum sanctioned WIG block size (F1): a lead-measure block never drops below this. */
    public static final int MIN_WIG_BLOCK_MINUTES = 45;

    /** The sanctioned defaults (planner engine doc; F1 pace constants, Comité 2026-07-09). */
    public static final PlannerConstraints DEFAULT =
        new PlannerConstraints(5, 14, 36, 45, 0.05, 100.0, 0.10, 3, 2, 4);

    public PlannerConstraints {
        requirePositive(minSleepSamples, "minSleepSamples");
        requirePositive(sleepHistoryDays, "sleepHistoryDays");
        requirePositive(sleepFreshnessHours, "sleepFreshnessHours");
        if (wigBlockMinutes < MIN_WIG_BLOCK_MINUTES) {
            throw new IllegalArgumentException(
                "wigBlockMinutes must be >= " + MIN_WIG_BLOCK_MINUTES + ": " + wigBlockMinutes);
        }
        if (pacePrecisionEpsilon <= 0.0 || pacePrecisionEpsilon > 1.0) {
            throw new IllegalArgumentException(
                "pacePrecisionEpsilon must be in (0, 1]: " + pacePrecisionEpsilon);
        }
        requirePositive(maxRequiredPace, "maxRequiredPace");
        if (hysteresisMargin < 0.0 || hysteresisMargin > 1.0) {
            throw new IllegalArgumentException("hysteresisMargin must be in [0, 1]: " + hysteresisMargin);
        }
        if (degradedStreakThreshold < 1) {
            throw new IllegalArgumentException(
                "degradedStreakThreshold must be positive: " + degradedStreakThreshold);
        }
        if (degradedUrgentCount < 0) {
            throw new IllegalArgumentException("degradedUrgentCount must be non-negative: " + degradedUrgentCount);
        }
        if (highLoadDrainFloor < 1 || highLoadDrainFloor > 5) {
            throw new IllegalArgumentException("highLoadDrainFloor must be in [1, 5]: " + highLoadDrainFloor);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    private static void requirePositive(double value, String name) {
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
