package com.hyperbrain.prioritizer.domain.model;

/**
 * The four weights of the Priority Score (v2), applied to the already-normalized factors:
 * {@code P = impact·wImpact + urgency·wUrgency + effortInv·wEffort + alignment·wAlignment}.
 *
 * <p>The weights are a weighted-scoring heuristic (RICE/ICE family), a deliberate starting point
 * rather than empirical constants — the engine doc mandates they be user-configurable. This record
 * is that seam: {@link #DEFAULT} carries the sanctioned 40/30/10/20 split (Daniel, 2026-07-09), and
 * a settings adapter can supply an alternative instance without touching the calculator.
 *
 * <p>The alignment weight stays at {@code 0.2} by decision F4: the WIG is protected downstream by a
 * hard reservation in the Planner, not by inflating this weight — the Prioritizer computes the
 * honest score only.
 *
 * @param wImpact    weight of the normalized impact factor
 * @param wUrgency   weight of the normalized urgency factor
 * @param wEffort    weight of the inverted-effort factor (low on purpose: Quick Wins break ties)
 * @param wAlignment weight of the MCI-alignment factor (fixed at 0.2 by decision F4)
 */
public record PriorityWeights(double wImpact, double wUrgency, double wEffort, double wAlignment) {

    /** The sanctioned default split (Daniel, 2026-07-09): impact 40, urgency 30, effort 10, alignment 20. */
    public static final PriorityWeights DEFAULT = new PriorityWeights(0.4, 0.3, 0.1, 0.2);

    public PriorityWeights {
        requireUnitInterval(wImpact, "wImpact");
        requireUnitInterval(wUrgency, "wUrgency");
        requireUnitInterval(wEffort, "wEffort");
        requireUnitInterval(wAlignment, "wAlignment");
    }

    private static void requireUnitInterval(double weight, String name) {
        if (weight < 0.0 || weight > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]: " + weight);
        }
    }
}
