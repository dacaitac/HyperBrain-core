package com.hyperbrain.planner.domain.model;

import java.util.UUID;

/**
 * The learned unit cost ({@code cu}) of one task (spike #63): the estimated minutes a single
 * subtask of this task takes, and the input the Planner and the UserStateReadModel (#61) multiply
 * by the pending-subtask count to size the remaining effort.
 *
 * <p>{@code coldStart} distinguishes a value still resting on the human prior
 * ({@code estimated_minutes / total_subtasks}) from one the EWMA has learned from real settled
 * blocks. The estimator only trusts observed reality once at least {@link #MIN_OBSERVATIONS}
 * valid observations have accrued; below that it stays on the prior. {@code cu} may be null when
 * neither signal exists (no observations and no usable human estimate).
 *
 * @param taskId          the task this cost belongs to
 * @param cu              learned minutes per subtask; null when no signal is available
 * @param observationCount valid observations that fed the EWMA
 * @param coldStart       true while resting on the human prior ({@code observationCount < MIN})
 */
public record LearnedUnitCost(UUID taskId, Double cu, int observationCount, boolean coldStart) {

    /** Minimum valid observations before the EWMA is trusted over the human prior (spike #63). */
    public static final int MIN_OBSERVATIONS = 3;

    /**
     * Builds a cold-start cost resting on the human prior.
     *
     * @param taskId           the task
     * @param cu               the prior {@code estimated_minutes / total_subtasks}, or null if none
     * @param observationCount valid observations seen so far (strictly below {@link #MIN_OBSERVATIONS})
     * @return a cold-start cost
     */
    public static LearnedUnitCost coldStart(UUID taskId, Double cu, int observationCount) {
        return new LearnedUnitCost(taskId, cu, observationCount, true);
    }

    /**
     * Builds a learned cost the EWMA produced from enough real observations.
     *
     * @param taskId           the task
     * @param cu               the EWMA-smoothed minutes per subtask
     * @param observationCount valid observations that fed the EWMA (at least {@link #MIN_OBSERVATIONS})
     * @return a learned cost
     */
    public static LearnedUnitCost learned(UUID taskId, double cu, int observationCount) {
        return new LearnedUnitCost(taskId, cu, observationCount, false);
    }
}
