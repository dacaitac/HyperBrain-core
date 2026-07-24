package com.hyperbrain.planner.infrastructure.telemetry;

import com.hyperbrain.planner.domain.model.SleepScoreConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calibration surface for the {@code sleep_score} formula (ADR-016 v1.4.0), bound from
 * {@code app.telemetry.sleep-score.*}. Kept out of the domain calculator so weights, phase bands and
 * the duration window are configuration, not hard-coded formula constants. Every value is a sanctioned
 * MVP default pending Daniel's ratification; an absent section falls back to
 * {@link SleepScoreConfig#defaults()} so a partial {@code application.yml} still yields a valid formula.
 */
@ConfigurationProperties(prefix = "app.telemetry.sleep-score")
public record SleepScoreProperties(
    Weights weights,
    DurationHours durationHours,
    Efficiency efficiency,
    Band deepBand,
    Band remBand,
    WasoMinutes wasoMinutes
) {

    /** Sub-score weights; must sum to 1.0 (validated by {@link SleepScoreConfig}). */
    public record Weights(double duration, double efficiency, double deep, double rem, double waso) {
    }

    /** Duration sub-score breakpoints, in hours (flat 100 over [fullLower, plateauUpper]). */
    public record DurationHours(double zeroFloor, double fullLower, double plateauUpper,
                                double oversleepSlopePerHour) {
    }

    /** Efficiency sub-score: 0 at {@code zero}, 100 at {@code full} (fractions in {@code [0, 1]}). */
    public record Efficiency(double zero, double full) {
    }

    /** Trapezoid band for a phase fraction: 100 on [fullLow, fullHigh], 0 at zeroLow/zeroHigh. */
    public record Band(double zeroLow, double fullLow, double fullHigh, double zeroHigh) {
    }

    /** Fragmentation sub-score: 100 at ≤{@code fullMax} minutes, 0 at ≥{@code zero} minutes. */
    public record WasoMinutes(double fullMax, double zero) {
    }

    /** Maps the bound properties onto the domain config, per-section fallback to the sanctioned defaults. */
    public SleepScoreConfig toConfig() {
        SleepScoreConfig d = SleepScoreConfig.defaults();
        Weights w = weights != null ? weights
            : new Weights(d.durationWeight(), d.efficiencyWeight(), d.deepWeight(), d.remWeight(), d.wasoWeight());
        DurationHours dur = durationHours != null ? durationHours
            : new DurationHours(d.zeroFloorHours(), d.fullLowerHours(), d.plateauUpperHours(), d.oversleepSlopePerHour());
        Efficiency eff = efficiency != null ? efficiency : new Efficiency(d.efficiencyZero(), d.efficiencyFull());
        Band deep = deepBand != null ? deepBand
            : new Band(d.deepZeroLow(), d.deepFullLow(), d.deepFullHigh(), d.deepZeroHigh());
        Band rem = remBand != null ? remBand
            : new Band(d.remZeroLow(), d.remFullLow(), d.remFullHigh(), d.remZeroHigh());
        WasoMinutes waso = wasoMinutes != null ? wasoMinutes
            : new WasoMinutes(d.wasoFullMaxMinutes(), d.wasoZeroMinutes());
        return new SleepScoreConfig(
            w.duration(), w.efficiency(), w.deep(), w.rem(), w.waso(),
            dur.zeroFloor(), dur.fullLower(), dur.plateauUpper(), dur.oversleepSlopePerHour(),
            eff.zero(), eff.full(),
            deep.zeroLow(), deep.fullLow(), deep.fullHigh(), deep.zeroHigh(),
            rem.zeroLow(), rem.fullLow(), rem.fullHigh(), rem.zeroHigh(),
            waso.fullMax(), waso.zero());
    }
}
