package com.hyperbrain.planner.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Everything the {@code LearnedUnitCostCalculator} needs to derive one task's learned unit cost
 * (spike #63), gathered by the read port in a single pass so the pure calculator stays free of
 * persistence:
 *
 * <ul>
 *   <li>{@code observations} — the task's valid settled-block observations ordered chronologically
 *       by {@code settled_at} (oldest first), already filtered of never-executed EXPIRED blocks;</li>
 *   <li>{@code estimatedMinutes} / {@code totalSubtasks} — the human estimate and subtask count that
 *       feed the cold-start prior ({@code estimatedMinutes / totalSubtasks}) until enough
 *       observations accrue.</li>
 * </ul>
 *
 * @param taskId           the executable whose unit cost is being learned
 * @param observations     valid settled-block observations, oldest first; never null (may be empty)
 * @param estimatedMinutes the task's {@code estimated_minutes}; may be null when unestimated
 * @param totalSubtasks    total user subtasks of the task; used only for the cold-start prior
 */
public record TaskCostInputs(
    UUID taskId,
    List<SettledObservation> observations,
    Integer estimatedMinutes,
    int totalSubtasks
) {

    public TaskCostInputs {
        observations = observations == null ? List.of() : List.copyOf(observations);
        if (totalSubtasks < 0) {
            throw new IllegalArgumentException("totalSubtasks must be non-negative: " + totalSubtasks);
        }
    }
}
