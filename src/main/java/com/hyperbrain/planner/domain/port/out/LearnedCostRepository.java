package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.TaskCostInputs;

import java.util.UUID;

/**
 * Read-only port over the settled-block history feeding the learned unit cost (spike #63). It
 * projects, for one task, its chronologically ordered valid observations plus the human prior
 * inputs — never mutating state — so the {@code LearnedUnitCostCalculator} stays a pure domain
 * service. The JDBC adapter derives {@code imputedSubtaskCount} by joining
 * {@code core_executable} on {@code imputed_time_block_id} (index
 * {@code idx_core_executable_imputed_block}); the count is not a column of {@code core_time_block}.
 */
public interface LearnedCostRepository {

    /**
     * Gathers the learning inputs of one task: its valid settled-block observations ordered
     * oldest-first by {@code settled_at}, and the {@code estimated_minutes} / total-subtask prior.
     *
     * <p>The projection includes {@code SETTLED} blocks and {@code EXPIRED} blocks whose
     * {@code actual_duration_minutes} is non-null; never-executed EXPIRED blocks (null actual)
     * are excluded so they are not imputed as zero-minute work.
     *
     * @param taskId the executable whose unit cost is being learned
     * @return the calculator inputs; observations may be empty when nothing has settled yet
     */
    TaskCostInputs loadCostInputs(UUID taskId);
}
