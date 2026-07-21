package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.SchedulableExecutable;

/**
 * Pure domain helper that sizes a block by an executable's remaining effort (ADR-013 D4, planner
 * engine doc). Three branches, in priority order:
 *
 * <ul>
 *   <li><b>With subtasks:</b> {@code remaining = pendingSubtasks × cu} — the task learned (or
 *       cold-start-primed) its per-subtask cost via the {@code LearnedUnitCostCalculator};</li>
 *   <li><b>With estimate:</b> {@code remaining = max(estimatedMinutes − settledActualMinutes, 0)}
 *       — the a-priori estimate (from the execution profile or derived from {@code end_time −
 *       start_time} at query time) net of work already spent;</li>
 *   <li><b>Cold-start fallback:</b> {@code COLD_START_MINUTES} when no signal exists, so the
 *       generator can still place the task rather than excluding it with
 *       {@code NO_REMAINING_EFFORT}.</li>
 * </ul>
 */
public final class RemainingEffortCalculator {

    /** Minutes assigned when no estimate and no date range is available. */
    static final int COLD_START_MINUTES = 30;

    private RemainingEffortCalculator() {
    }

    /**
     * Computes the remaining effort in minutes for one executable.
     *
     * @param executable the schedulable executable carrying the effort inputs; never null
     * @return remaining minutes, always positive (at least {@code COLD_START_MINUTES})
     */
    public static int remainingMinutes(SchedulableExecutable executable) {
        Double cu = executable.learnedUnitCost();
        if (cu != null) {
            double remaining = executable.pendingSubtasks() * cu;
            return (int) Math.max(0, Math.round(remaining));
        }
        Integer estimated = executable.estimatedMinutes();
        if (estimated == null) {
            return COLD_START_MINUTES;
        }
        return Math.max(0, estimated - executable.settledActualMinutes());
    }
}
