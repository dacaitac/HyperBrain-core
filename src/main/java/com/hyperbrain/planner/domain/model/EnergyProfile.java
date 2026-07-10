package com.hyperbrain.planner.domain.model;

/**
 * The resolved load parameters for one day (ADR-016 — the eje de energía). The Planner reads last
 * night's {@code sleep_score}, maps it through {@link EnergyThresholds} onto an {@link EnergyTier},
 * and lands here:
 *
 * <ul>
 *   <li>{@code chaosMarginFraction} (F3) — the fraction of the planning window held back as slack
 *       for the unexpected;</li>
 *   <li>{@code highLoadQuota} (F6) — how many high {@code energy_drain} blocks the day admits.</li>
 * </ul>
 *
 * <p>{@code criterion} is the human-readable {@code Sleep Score → margin → quota} chain the agenda
 * must surface (legibilidad obligatoria, Triángulo de Control): the day is never trimmed silently.
 *
 * @param tier                the resolved energy band
 * @param chaosMarginFraction F3 — chaos margin in {@code [0, 1]}
 * @param highLoadQuota       F6 — the maximum count of high-load blocks; never negative
 * @param criterion           the readable trimming criterion surfaced to the user; never null
 */
public record EnergyProfile(
    EnergyTier tier,
    double chaosMarginFraction,
    int highLoadQuota,
    String criterion
) {

    public EnergyProfile {
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        if (chaosMarginFraction < 0.0 || chaosMarginFraction > 1.0) {
            throw new IllegalArgumentException(
                "chaosMarginFraction must be in [0, 1]: " + chaosMarginFraction);
        }
        if (highLoadQuota < 0) {
            throw new IllegalArgumentException("highLoadQuota must be non-negative: " + highLoadQuota);
        }
        if (criterion == null || criterion.isBlank()) {
            throw new IllegalArgumentException("criterion must not be blank");
        }
    }
}
