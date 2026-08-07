package com.hyperbrain.planner.domain.model;

/**
 * The calibrable domain constants of the eje de energía (ADR-016): the {@code sleep_score} cut points
 * that split the day into {@link EnergyTier}s, and the goal-reservation budget each tier fixes. Like
 * {@code AlignmentWeights} in the Prioritizer, this is a calibration seam — a settings adapter can
 * supply an alternative instance without touching {@code EnergyResolver}.
 *
 * <p>A score strictly below {@code lowCeiling} is LOW; at or above {@code highFloor} is HIGH; anything
 * between is NEUTRAL. The budget tracks cognitive capacity — LOW 2, NEUTRAL 3, HIGH 3.
 *
 * <p>The three chaos margins that used to live here are gone with the mechanism they calibrated
 * (ADR-040 D1). The sleep signal itself is untouched: it is context for the intelligent layer, and
 * only its use as a planning restriction was retired.
 *
 * <p>The cut points are {@code lowCeiling = 60}, {@code highFloor = 80} on the common 0–100 scale.
 *
 * @param lowCeiling        exclusive upper bound of the LOW band (a score below this is LOW)
 * @param highFloor         inclusive lower bound of the HIGH band (a score at/above this is HIGH)
 * @param lowQuota          goal-reservation budget for LOW; never negative
 * @param neutralQuota      budget for NEUTRAL (also the no-signal default); never negative
 * @param highQuota         budget for HIGH; never negative
 */
public record EnergyThresholds(
    int lowCeiling,
    int highFloor,
    int lowQuota,
    int neutralQuota,
    int highQuota
) {

    /** The sanctioned defaults (Comité 2026-07-09). */
    public static final EnergyThresholds DEFAULT = new EnergyThresholds(60, 80, 2, 3, 3);

    public EnergyThresholds {
        if (lowCeiling > highFloor) {
            throw new IllegalArgumentException(
                "lowCeiling must not exceed highFloor: " + lowCeiling + " > " + highFloor);
        }
        requireNonNegative(lowQuota, "lowQuota");
        requireNonNegative(neutralQuota, "neutralQuota");
        requireNonNegative(highQuota, "highQuota");
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative: " + value);
        }
    }
}
