package com.hyperbrain.planner.domain.model;

/**
 * Calibration constants for the {@code sleep_score} formula (ADR-016 v1.4.0, fixed and validated by
 * the expert committee). Every value is a <b>sanctioned MVP default pending Daniel's ratification</b>
 * (a domain formula) and is overridable from {@code app.telemetry.sleep-score.*}.
 *
 * <p>The score is a weighted sum of five sub-scores, each in {@code [0, 100]}:
 * <ul>
 *   <li><b>Duration</b> ({@code durationWeight}, default 45%): full flat plateau over
 *       {@code [fullLowerHours, plateauUpperHours]} = [7h, 10h] so 9–10h never penalizes; linear down
 *       to 0 at {@code zeroFloorHours} (3h); a very gentle {@code oversleepSlopePerHour} beyond 10h.</li>
 *   <li><b>Efficiency</b> ({@code efficiencyWeight}, 30%): TST/TIB; full at {@code efficiencyFull}
 *       (90%), linear {@code efficiencyZero}→{@code efficiencyFull} (75%→90%).</li>
 *   <li><b>Deep %N3</b> ({@code deepWeight}, 10%): trapezoid band [{@code deepFullLow},
 *       {@code deepFullHigh}] = [13%, 23%], falloff to 0 at the zero edges.</li>
 *   <li><b>REM %</b> ({@code remWeight}, 10%): trapezoid band [{@code remFullLow}, {@code remFullHigh}]
 *       = [20%, 25%]; the 18–27% tolerance maps to ≈80 via the zero edges.</li>
 *   <li><b>Fragmentation WASO</b> ({@code wasoWeight}, 5%): full at ≤{@code wasoFullMaxMinutes} (20 min),
 *       linear to 0 at {@code wasoZeroMinutes} (60 min).</li>
 * </ul>
 *
 * <p>When the payload carries no stage breakdown the calculator drops the three phase sub-scores and
 * renormalizes duration + efficiency to 60/40 (derived from their weight ratio), flagging low
 * confidence — a missing breakdown never yields 0.
 */
public record SleepScoreConfig(
    double durationWeight,
    double efficiencyWeight,
    double deepWeight,
    double remWeight,
    double wasoWeight,
    double zeroFloorHours,
    double fullLowerHours,
    double plateauUpperHours,
    double oversleepSlopePerHour,
    double efficiencyZero,
    double efficiencyFull,
    double deepZeroLow,
    double deepFullLow,
    double deepFullHigh,
    double deepZeroHigh,
    double remZeroLow,
    double remFullLow,
    double remFullHigh,
    double remZeroHigh,
    double wasoFullMaxMinutes,
    double wasoZeroMinutes
) {

    private static final double WEIGHT_SUM_TOLERANCE = 1e-6;

    public SleepScoreConfig {
        double weightSum = durationWeight + efficiencyWeight + deepWeight + remWeight + wasoWeight;
        if (Math.abs(weightSum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException("sleep-score weights must sum to 1.0 but summed to " + weightSum);
        }
        requireOrdered("duration window", zeroFloorHours, fullLowerHours, plateauUpperHours);
        requireOrdered("efficiency", efficiencyZero, efficiencyFull);
        requireOrdered("deep band", deepZeroLow, deepFullLow, deepFullHigh, deepZeroHigh);
        requireOrdered("rem band", remZeroLow, remFullLow, remFullHigh, remZeroHigh);
        requireOrdered("waso", wasoFullMaxMinutes, wasoZeroMinutes);
    }

    /** Returns the sanctioned ADR-016 v1.4.0 defaults (pending Daniel's ratification). */
    public static SleepScoreConfig defaults() {
        return new SleepScoreConfig(
            0.45, 0.30, 0.10, 0.10, 0.05,
            3.0, 7.0, 10.0, 15.0,
            0.75, 0.90,
            0.02, 0.13, 0.23, 0.35,
            0.10, 0.20, 0.25, 0.35,
            20.0, 60.0);
    }

    /** Duration weight share once the phase sub-scores are dropped (no breakdown): 45/(45+30) = 0.6. */
    public double durationWeightNoPhase() {
        return durationWeight / (durationWeight + efficiencyWeight);
    }

    /** Efficiency weight share once the phase sub-scores are dropped: 30/(45+30) = 0.4. */
    public double efficiencyWeightNoPhase() {
        return efficiencyWeight / (durationWeight + efficiencyWeight);
    }

    private static void requireOrdered(String label, double... breakpoints) {
        for (int i = 1; i < breakpoints.length; i++) {
            if (breakpoints[i] < breakpoints[i - 1]) {
                throw new IllegalArgumentException(label + " breakpoints must be non-decreasing");
            }
        }
    }
}
