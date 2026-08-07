package com.hyperbrain.core.domain.model;

/**
 * What one run of the ADR-040 D4 day-close sweep did, counted per behaviour. Returned so callers
 * and tests can assert on the outcome instead of inferring it from side effects.
 *
 * @param closedAsFailed how many executables left the inventory as a sanctioned miss
 * @param rescheduled    how many tasks were moved onto the reference day
 * @param dateCleared    how many purchases returned to the dateless bag
 */
public record OverdueSweepReport(int closedAsFailed, int rescheduled, int dateCleared) {

    /** @return true when the run changed nothing — the expected outcome of a second run */
    public boolean isEmpty() {
        return closedAsFailed == 0 && rescheduled == 0 && dateCleared == 0;
    }

    /** @return the total number of rows this run acted on */
    public int total() {
        return closedAsFailed + rescheduled + dateCleared;
    }
}
