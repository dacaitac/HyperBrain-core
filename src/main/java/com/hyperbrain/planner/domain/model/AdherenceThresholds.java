package com.hyperbrain.planner.domain.model;

/**
 * The calibrable constants of the H0 adherence rollup (#17). Kept in the domain (framework-free) and
 * injected into {@link com.hyperbrain.planner.domain.service.AdherenceCalculator}, so no formula
 * constant is hard-coded in a service.
 *
 * <p><b>Pending Daniel (domain formula).</b> Both values are sanctioned MVP defaults, not final
 * domain law: {@code executedMinMinutes} is the temporal tolerance that turns a settled block into
 * an "executed" one, and {@code abandonmentAdherenceThreshold} is the adherence below which a
 * replan-less day is flagged abandoned.
 *
 * @param executedMinMinutes            minimum settled minutes for a block to count as executed;
 *                                      must be &ge; 0
 * @param abandonmentAdherenceThreshold adherence (0..1) under which a day with zero replans is
 *                                      abandoned; must be within [0, 1]
 */
public record AdherenceThresholds(int executedMinMinutes, double abandonmentAdherenceThreshold) {

    public AdherenceThresholds {
        if (executedMinMinutes < 0) {
            throw new IllegalArgumentException("executedMinMinutes must be >= 0: " + executedMinMinutes);
        }
        if (abandonmentAdherenceThreshold < 0.0 || abandonmentAdherenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                "abandonmentAdherenceThreshold must be within [0, 1]: " + abandonmentAdherenceThreshold);
        }
    }
}
