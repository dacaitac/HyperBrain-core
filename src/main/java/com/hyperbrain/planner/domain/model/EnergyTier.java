package com.hyperbrain.planner.domain.model;

/**
 * The graded energy band the Planner derives from last night's {@code sleep_score}
 * (ADR-016 — the eje de energía, Comité 2026-07-09). The band is a direct, stepped mapping of the
 * raw score onto the two load parameters F3 (chaos margin) and F6 (high-load quota); it is
 * deliberately <b>not</b> a latent "energy" variable.
 *
 * <p>The band modulates the day's <em>load</em>, never the sleep frontier (an orthogonal axis): the
 * frontier is derived from the observed bedtime/wake, never from the Sleep Score.
 */
public enum EnergyTier {
    /** Poor sleep: widen the chaos margin, tighten the high-load quota. */
    LOW,
    /** Neutral — the default when there is no fresh sleep signal. */
    NEUTRAL,
    /** Good sleep: narrow the chaos margin, allow more high-load blocks. */
    HIGH
}
