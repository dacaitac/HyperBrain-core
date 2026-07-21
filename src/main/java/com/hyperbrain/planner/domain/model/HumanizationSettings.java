package com.hyperbrain.planner.domain.model;

import java.time.LocalTime;
import java.util.List;

/**
 * The calibrable constants of the <b>humanized deterministic floor</b> (H1, HU-01c): the layer that
 * turns the raw placement of the {@code AgendaGenerator} (flat, back-to-back blocks) into a day a
 * human can actually live. Grouped here — like {@link PlannerConstraints} — so a settings adapter can
 * supply an alternative instance from configuration ({@code app.planner.humanize.*}) without touching
 * the domain services; no humanization constant is hard-coded in a service.
 *
 * <p>This output serves a double duty (HU-01c): it is both the deterministic <em>baseline</em> shipped
 * before the LLM tier lands, and the <em>DEGRADED fallback</em> of the propose-then-validate loop
 * (ADR-019) — when the LLM fails or the {@code AgendaValidator} rejects its proposal, this humanized
 * floor is returned instead of the raw generator output.
 *
 * <p><b>Every default here is a domain calibration reserved to Daniel</b> (like the H0 tolerances):
 * the values below are sanctioned MVP defaults to be ratified, not invented formulas.
 *
 * @param transitionBufferMinutes short spacing left after each execution block to avoid gluing tasks
 *                                back-to-back (H1 rule 1); materialized only as spacing, never as an
 *                                Apple-written block; default 5
 * @param mealWindows             the protected meal anchors the floor never fills with work (H1 rule 2);
 *                                never null, may be empty; defaults lunch 12:30–13:30 and dinner
 *                                19:00–20:00, resolved against the run's zone
 * @param minBlockMinutes         the minimum viable block duration; blocks below it are dropped as
 *                                slivers rather than fragmenting the day (H1 rule 3); default 15
 * @param batchBandWidth          the priority-score tolerance within which adjacent executables are
 *                                considered comparable and may be regrouped by context to cut
 *                                context-switching (H1 rule 4) — a tie-break inside a band, never a
 *                                reordering across priority bands; default 0.10 (DOMAIN FORMULA, Daniel)
 * @param occupancyMinFraction    the lower edge of the sanctioned occupancy band — an aspirational
 *                                floor (the day cannot invent work), surfaced but not force-filled
 *                                (H1 rule 6); default 0.75
 * @param occupancyMaxFraction    the upper edge of the occupancy band — a hard cap: the day is never
 *                                packed past it, leaving deliberate slack (H1 rule 6); default 0.85
 */
public record HumanizationSettings(
    int transitionBufferMinutes,
    List<MealWindow> mealWindows,
    int minBlockMinutes,
    double batchBandWidth,
    double occupancyMinFraction,
    double occupancyMaxFraction
) {

    /**
     * The sanctioned MVP defaults (H1, HU-01c) — <b>pending Daniel's ratification</b>: 5-min buffers,
     * lunch 12:30–13:30 and dinner 19:00–20:00, a 15-min minimum block, a 0.10 batching band, and a
     * 75–85% occupancy band.
     */
    public static final HumanizationSettings DEFAULT = new HumanizationSettings(
        5,
        List.of(
            new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30)),
            new MealWindow("dinner", LocalTime.of(19, 0), LocalTime.of(20, 0))),
        15,
        0.10,
        0.75,
        0.85);

    /**
     * A no-op instance that leaves the raw placement untouched: no buffers, no meal walls, no
     * anti-fragmentation, no batching, and a full-window occupancy cap. Used as the backward-compatible
     * default of the bare {@code AgendaGenerator} constructors so existing behavior is unchanged unless
     * humanization is explicitly wired in.
     */
    public static final HumanizationSettings NO_OP = new HumanizationSettings(
        0, List.of(), 0, 0.0, 0.0, 1.0);

    public HumanizationSettings {
        if (transitionBufferMinutes < 0) {
            throw new IllegalArgumentException(
                "transitionBufferMinutes must be non-negative: " + transitionBufferMinutes);
        }
        if (minBlockMinutes < 0) {
            throw new IllegalArgumentException("minBlockMinutes must be non-negative: " + minBlockMinutes);
        }
        if (batchBandWidth < 0.0 || batchBandWidth > 1.0) {
            throw new IllegalArgumentException("batchBandWidth must be in [0, 1]: " + batchBandWidth);
        }
        if (occupancyMinFraction < 0.0 || occupancyMinFraction > 1.0) {
            throw new IllegalArgumentException(
                "occupancyMinFraction must be in [0, 1]: " + occupancyMinFraction);
        }
        if (occupancyMaxFraction <= 0.0 || occupancyMaxFraction > 1.0) {
            throw new IllegalArgumentException(
                "occupancyMaxFraction must be in (0, 1]: " + occupancyMaxFraction);
        }
        if (occupancyMaxFraction < occupancyMinFraction) {
            throw new IllegalArgumentException(
                "occupancyMaxFraction must be >= occupancyMinFraction: "
                    + occupancyMinFraction + " .. " + occupancyMaxFraction);
        }
        mealWindows = mealWindows == null ? List.of() : List.copyOf(mealWindows);
    }
}
