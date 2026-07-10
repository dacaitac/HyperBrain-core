package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.SchedulableExecutable;

/**
 * Pure domain helper that sizes a block by an executable's remaining effort (ADR-013 D4, planner
 * engine doc). Two branches, selected by whether the task has a learned unit cost:
 *
 * <ul>
 *   <li><b>With subtasks:</b> {@code remaining = pendingSubtasks × cu} — the task learned (or
 *       cold-start-primed) its per-subtask cost via the {@code LearnedUnitCostCalculator};</li>
 *   <li><b>Without subtasks:</b> {@code remaining = max(estimatedMinutes − settledActualMinutes, 0)}
 *       — the a-priori estimate net of work already spent.</li>
 * </ul>
 *
 * <p>Returns {@code 0} when no signal exists (no cu and no estimate): a zero-effort executable has
 * nothing to schedule and the generator excludes it with {@code NO_REMAINING_EFFORT}, never
 * fabricating a duration.
 */
public final class RemainingEffortCalculator {

    private RemainingEffortCalculator() {
    }

    /**
     * Computes the remaining effort in minutes for one executable.
     *
     * @param executable the schedulable executable carrying the effort inputs; never null
     * @return remaining minutes, never negative (0 when there is no usable signal)
     */
    public static int remainingMinutes(SchedulableExecutable executable) {
        Double cu = executable.learnedUnitCost();
        if (cu != null) {
            double remaining = executable.pendingSubtasks() * cu;
            return (int) Math.max(0, Math.round(remaining));
        }
        Integer estimated = executable.estimatedMinutes();
        if (estimated == null) {
            return 0;
        }
        return Math.max(0, estimated - executable.settledActualMinutes());
    }
}
