package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.SleepScoreConfig;
import com.hyperbrain.planner.domain.model.SleepScoreResult;
import com.hyperbrain.planner.domain.model.SleepStageSample;

import java.time.Duration;

/**
 * Pure domain service computing the per-night {@code sleep_score} in {@code [0, 100]} from a device
 * sleep session (ADR-016 v1.4.0, fixed and validated by the expert committee).
 *
 * <p>The score is a weighted sum of five sub-scores — Duration (45%), Efficiency (30%), Deep %N3
 * (10%), REM % (10%) and Fragmentation/WASO (5%) — each a piecewise-linear curve over the ranges in
 * {@link SleepScoreConfig}. Total sleep time (TST) is the sum of asleep segments; time in bed (TIB) is
 * the session window ({@code end - start}), robust to a missing unclassified-in-bed segment; sleep
 * efficiency is {@code TST/TIB} clamped to {@code [0, 1]}.
 *
 * <p><b>Robustness (never 0 for missing data):</b> when the session carries no stage breakdown the
 * three phase sub-scores are dropped and Duration + Efficiency are renormalized to 60/40, with a
 * low-confidence flag. A session with no sleep at all (TST = 0) is not scorable and raises
 * {@link IllegalArgumentException}, which the normalizer records as an ERROR — distinct from a merely
 * incomplete but scorable night.
 *
 * <p>All inputs are pure values and the injected {@link SleepScoreConfig}; the result is deterministic.
 */
public class SleepScoreCalculator {

    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final double FULL = 100.0;

    private final SleepScoreConfig config;

    /** Creates a calculator using the sanctioned ADR-016 v1.4.0 defaults. */
    public SleepScoreCalculator() {
        this(SleepScoreConfig.defaults());
    }

    /**
     * Creates a calculator with explicit calibration.
     *
     * @param config the weights, bands and duration window; never null
     */
    public SleepScoreCalculator(SleepScoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("sleep-score config must not be null");
        }
        this.config = config;
    }

    /**
     * Computes the sleep score for one night.
     *
     * @param sample the session's stage durations and window; never null
     * @return the score, the low-confidence flag and the backing metrics/sub-scores
     * @throws IllegalArgumentException when the night is not scorable (no sleep recorded)
     */
    public SleepScoreResult score(SleepStageSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("sleep sample must not be null");
        }
        long tstSeconds = sample.totalSleepSeconds();
        if (tstSeconds <= 0) {
            throw new IllegalArgumentException("sleep sample has no asleep time; night is not scorable");
        }
        double tibSeconds = Duration.between(sample.start(), sample.end()).toSeconds();
        double tstHours = tstSeconds / SECONDS_PER_HOUR;
        double efficiency = clampFraction(tstSeconds / tibSeconds);
        double wasoMinutes = sample.awakeSeconds() / SECONDS_PER_MINUTE;
        double deepFraction = (double) sample.deepSeconds() / tstSeconds;
        double remFraction = (double) sample.remSeconds() / tstSeconds;

        double durationSub = durationSubScore(tstHours);
        double efficiencySub = efficiencySubScore(efficiency);

        if (!sample.hasPhaseBreakdown()) {
            double score = config.durationWeightNoPhase() * durationSub
                + config.efficiencyWeightNoPhase() * efficiencySub;
            return new SleepScoreResult(round(score), true, tstHours, efficiency,
                deepFraction, remFraction, wasoMinutes, durationSub, efficiencySub, null, null, null);
        }

        double deepSub = trapezoid(deepFraction,
            config.deepZeroLow(), config.deepFullLow(), config.deepFullHigh(), config.deepZeroHigh());
        double remSub = trapezoid(remFraction,
            config.remZeroLow(), config.remFullLow(), config.remFullHigh(), config.remZeroHigh());
        double wasoSub = wasoSubScore(wasoMinutes);

        double score = config.durationWeight() * durationSub
            + config.efficiencyWeight() * efficiencySub
            + config.deepWeight() * deepSub
            + config.remWeight() * remSub
            + config.wasoWeight() * wasoSub;
        return new SleepScoreResult(round(score), false, tstHours, efficiency,
            deepFraction, remFraction, wasoMinutes, durationSub, efficiencySub, deepSub, remSub, wasoSub);
    }

    private double durationSubScore(double tstHours) {
        if (tstHours <= config.zeroFloorHours()) {
            return 0.0;
        }
        if (tstHours < config.fullLowerHours()) {
            return rampUp(tstHours, config.zeroFloorHours(), config.fullLowerHours());
        }
        if (tstHours <= config.plateauUpperHours()) {
            return FULL;
        }
        double penalty = (tstHours - config.plateauUpperHours()) * config.oversleepSlopePerHour();
        return clampScore(FULL - penalty);
    }

    private double efficiencySubScore(double efficiency) {
        if (efficiency >= config.efficiencyFull()) {
            return FULL;
        }
        if (efficiency <= config.efficiencyZero()) {
            return 0.0;
        }
        return rampUp(efficiency, config.efficiencyZero(), config.efficiencyFull());
    }

    private double wasoSubScore(double wasoMinutes) {
        if (wasoMinutes <= config.wasoFullMaxMinutes()) {
            return FULL;
        }
        if (wasoMinutes >= config.wasoZeroMinutes()) {
            return 0.0;
        }
        return rampDown(wasoMinutes, config.wasoFullMaxMinutes(), config.wasoZeroMinutes());
    }

    /** Trapezoid: 100 on {@code [fullLow, fullHigh]}, linear to 0 at {@code zeroLow}/{@code zeroHigh}. */
    private static double trapezoid(double v, double zeroLow, double fullLow, double fullHigh, double zeroHigh) {
        if (v <= zeroLow || v >= zeroHigh) {
            return 0.0;
        }
        if (v < fullLow) {
            return rampUp(v, zeroLow, fullLow);
        }
        if (v > fullHigh) {
            return rampDown(v, fullHigh, zeroHigh);
        }
        return FULL;
    }

    /** Linear 0→100 as {@code v} moves from {@code lo} to {@code hi}. */
    private static double rampUp(double v, double lo, double hi) {
        return (v - lo) / (hi - lo) * FULL;
    }

    /** Linear 100→0 as {@code v} moves from {@code lo} to {@code hi}. */
    private static double rampDown(double v, double lo, double hi) {
        return (hi - v) / (hi - lo) * FULL;
    }

    private static double clampFraction(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clampScore(double v) {
        return Math.max(0.0, Math.min(FULL, v));
    }

    private static int round(double score) {
        return (int) Math.round(clampScore(score));
    }
}
