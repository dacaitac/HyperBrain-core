package com.hyperbrain.planner.domain.model;

import java.util.UUID;

/**
 * One executable the Planner may place in the day, projected from the aggregate state by the read
 * port with its Prioritizer score already computed (the floor <b>reads</b> {@code priority_score};
 * it never recomputes the Prioritizer). Carries exactly what the deterministic floor needs to size
 * and classify a block, keeping the domain services free of persistence.
 *
 * <p><b>Remaining-effort inputs (ADR-013 D4).</b> The generator sizes each block by the remaining
 * effort, choosing the branch by {@code learnedUnitCost}:
 * <ul>
 *   <li><b>with subtasks:</b> {@code pendingSubtasks × cu} — used when {@code learnedUnitCost} is
 *       present (the {@code LearnedUnitCostCalculator} already resolved it, cold-start or learned);</li>
 *   <li><b>without subtasks:</b> {@code max(estimatedMinutes − settledActualMinutes, 0)} — used when
 *       {@code learnedUnitCost} is null (no subtasks to multiply).</li>
 * </ul>
 *
 * @param id                   the {@code core_executable}; never null
 * @param type                 the executable kind; never null
 * @param priorityScore        the Prioritizer score already persisted, in {@code [0, 1]}; the ranking
 *                             key (highest first). Null is treated as the neutral floor when ranking.
 * @param inProgress           true when {@code status = IN_PROGRESS} (candidate for the "paused" list
 *                             when it ends up with no open block)
 * @param energyDrain          {@code core_execution_profile.energy_drain} on the 1–5 scale; null when
 *                             unprofiled (treated as not high-load)
 * @param learnedUnitCost      the per-subtask learned cost (cu); present only when the task has
 *                             subtasks — selects the with-subtasks effort branch
 * @param pendingSubtasks      count of pending user subtasks; used with {@code learnedUnitCost}
 * @param estimatedMinutes     {@code core_execution_profile.estimated_minutes}; used by the
 *                             without-subtasks branch; null when unestimated
 * @param settledActualMinutes Σ {@code actual_duration_minutes} of the task's settled blocks; the
 *                             work already spent, subtracted in the without-subtasks branch
 */
public record SchedulableExecutable(
    UUID id,
    ExecutableType type,
    Double priorityScore,
    boolean inProgress,
    Integer energyDrain,
    Double learnedUnitCost,
    int pendingSubtasks,
    Integer estimatedMinutes,
    int settledActualMinutes
) {

    public SchedulableExecutable {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (pendingSubtasks < 0) {
            throw new IllegalArgumentException("pendingSubtasks must be non-negative: " + pendingSubtasks);
        }
        if (settledActualMinutes < 0) {
            throw new IllegalArgumentException("settledActualMinutes must be non-negative: " + settledActualMinutes);
        }
    }

    /** @return the ranking key: the priority score, or the neutral floor {@code 0.0} when null */
    public double rankingScore() {
        return priorityScore == null ? 0.0 : priorityScore;
    }

    /**
     * Tells whether this executable is high-load for the F6 quota.
     *
     * @param drainFloor the {@code energy_drain} at/above which a block is high-load
     * @return true when profiled with {@code energy_drain ≥ drainFloor}
     */
    public boolean isHighLoad(int drainFloor) {
        return energyDrain != null && energyDrain >= drainFloor;
    }
}
