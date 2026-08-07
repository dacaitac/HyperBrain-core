package com.hyperbrain.planner.domain.model;

/**
 * The resolved energy reading for one day (ADR-016 — the eje de energía). The Planner reads last
 * night's {@code sleep_score} and maps it through {@link EnergyThresholds} onto an {@link EnergyTier}.
 *
 * <p><b>The sleep signal survives; its use as a planning restriction does not.</b> The chaos margin
 * this record used to carry held back a quarter to a third of the day as slack, and it was implemented
 * as a cut to the day's tail: with a 30% margin over a 06:00–22:00 day the placement limit fell to
 * 17:12, so the afternoon was dead by side effect rather than by decision. ADR-040 D1 retires it. What
 * remains is the reading itself, which is context for the intelligent layer, and the goal reservation
 * budget.
 *
 * @param tier          the resolved energy band
 * @param highLoadQuota how many goal reservations the day admits (F1); never negative
 * @param criterion     the readable {@code Sleep Score → tier} chain the agenda surfaces (legibilidad
 *                      obligatoria, Triángulo de Control): the day is never shaped silently; never null
 */
public record EnergyProfile(EnergyTier tier, int highLoadQuota, String criterion) {

    public EnergyProfile {
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        if (highLoadQuota < 0) {
            throw new IllegalArgumentException("highLoadQuota must be non-negative: " + highLoadQuota);
        }
        if (criterion == null || criterion.isBlank()) {
            throw new IllegalArgumentException("criterion must not be blank");
        }
    }
}
