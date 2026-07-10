package com.hyperbrain.planner.domain.model;

/**
 * One learning observation drawn from a settled {@code core_time_block} (spike #63): the gross
 * minutes actually executed inside the block against the number of user subtasks imputed to it.
 * The per-block unit cost is {@code actualDurationMinutes / imputedSubtaskCount} — how long one
 * subtask of this task really took in that block.
 *
 * <p>The read port yields these ordered chronologically by {@code settled_at} so the EWMA folds
 * them oldest-first. Only blocks that observed real execution reach here: {@code SETTLED} blocks,
 * and {@code EXPIRED} blocks only when their {@code actual_duration_minutes} is non-null (a never
 * executed block is settled with a null actual on purpose and must not be imputed as zero).
 *
 * @param actualDurationMinutes gross executed minutes of the block; never null in a valid
 *                              observation, and non-negative
 * @param imputedSubtaskCount   number of real user subtasks imputed to the block
 *                              ({@code COUNT(core_executable WHERE imputed_time_block_id = block AND
 *                              system_generated = false)} — focus-cut snapshots are excluded);
 *                              never negative
 */
public record SettledObservation(int actualDurationMinutes, int imputedSubtaskCount) {

    public SettledObservation {
        if (actualDurationMinutes < 0) {
            throw new IllegalArgumentException(
                "actualDurationMinutes must be non-negative: " + actualDurationMinutes);
        }
        if (imputedSubtaskCount < 0) {
            throw new IllegalArgumentException(
                "imputedSubtaskCount must be non-negative: " + imputedSubtaskCount);
        }
    }

    /**
     * Tells whether this observation carries usable signal. A block that imputed no subtask
     * ({@code imputedSubtaskCount == 0}) cannot yield a per-subtask cost — dividing by it is
     * undefined — so it is dropped from the EWMA rather than counted as an outlier.
     *
     * @return true when a per-subtask unit cost can be computed
     */
    public boolean isValid() {
        return imputedSubtaskCount > 0;
    }

    /**
     * Computes the observed per-subtask unit cost of this block.
     *
     * @return {@code actualDurationMinutes / imputedSubtaskCount} in minutes
     * @throws IllegalStateException if the observation is not {@link #isValid() valid}
     */
    public double unitCost() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot derive a unit cost from a block with no imputed subtasks");
        }
        return (double) actualDurationMinutes / imputedSubtaskCount;
    }
}
