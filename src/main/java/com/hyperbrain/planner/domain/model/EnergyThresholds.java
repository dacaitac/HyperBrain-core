package com.hyperbrain.planner.domain.model;

/**
 * The calibrable domain constants of the eje de energía (ADR-016, Comité 2026-07-09): the
 * {@code sleep_score} cut points that split the day into {@link EnergyTier}s, and the F3 chaos
 * margin / F6 high-load quota each tier fixes. Like {@code AlignmentWeights} in the Prioritizer,
 * this is a calibration seam — a settings adapter can supply an alternative instance without
 * touching {@code EnergyResolver}.
 *
 * <p><b>Defaults (Comité 2026-07-09, F2 — chaos margin decoupled from the quota).</b> A score strictly
 * below {@code lowCeiling} is LOW; at or above {@code highFloor} is HIGH; anything between is NEUTRAL.
 * The high-load quota tracks cognitive capacity — LOW 2, NEUTRAL 3, HIGH 3; the chaos margin protects
 * against external interruptions and stays high on a good day — LOW 35%, NEUTRAL 25%, HIGH 25% (it
 * does not drop to 20% on HIGH). The two axes are deliberately independent (Daniel's decision).
 *
 * <p>The cut points are {@code lowCeiling = 60}, {@code highFloor = 80} on the common 0–100 scale.
 *
 * @param lowCeiling        exclusive upper bound of the LOW band (a score below this is LOW)
 * @param highFloor         inclusive lower bound of the HIGH band (a score at/above this is HIGH)
 * @param lowMargin         F3 chaos margin for LOW, in {@code [0, 1]}
 * @param neutralMargin     F3 chaos margin for NEUTRAL (also the no-signal default), in {@code [0, 1]}
 * @param highMargin        F3 chaos margin for HIGH, in {@code [0, 1]}
 * @param lowQuota          F6 high-load quota for LOW; never negative
 * @param neutralQuota      F6 high-load quota for NEUTRAL (also the no-signal default); never negative
 * @param highQuota         F6 high-load quota for HIGH; never negative
 */
public record EnergyThresholds(
    int lowCeiling,
    int highFloor,
    double lowMargin,
    double neutralMargin,
    double highMargin,
    int lowQuota,
    int neutralQuota,
    int highQuota
) {

    /** The sanctioned defaults (Comité 2026-07-09, F2: margin decoupled from quota). */
    public static final EnergyThresholds DEFAULT =
        new EnergyThresholds(60, 80, 0.35, 0.25, 0.25, 2, 3, 3);

    public EnergyThresholds {
        if (lowCeiling > highFloor) {
            throw new IllegalArgumentException(
                "lowCeiling must not exceed highFloor: " + lowCeiling + " > " + highFloor);
        }
        requireUnitInterval(lowMargin, "lowMargin");
        requireUnitInterval(neutralMargin, "neutralMargin");
        requireUnitInterval(highMargin, "highMargin");
        requireNonNegative(lowQuota, "lowQuota");
        requireNonNegative(neutralQuota, "neutralQuota");
        requireNonNegative(highQuota, "highQuota");
    }

    private static void requireUnitInterval(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]: " + value);
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative: " + value);
        }
    }
}
