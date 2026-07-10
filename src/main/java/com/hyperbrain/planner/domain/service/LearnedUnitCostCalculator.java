package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LearnedUnitCost;
import com.hyperbrain.planner.domain.model.SettledObservation;
import com.hyperbrain.planner.domain.model.TaskCostInputs;

import java.util.List;

/**
 * Pure domain service that derives a task's learned unit cost ({@code cu}) — the estimated minutes
 * one of its subtasks takes — from settled-block history (spike #63; formula approved by Daniel
 * 2026-07-09, do not re-derive here).
 *
 * <p><b>Estimator.</b> A per-task EWMA with {@code α = 0.3} folded over the valid observations
 * oldest-first:
 * <pre>{@code cu_t = α·obs_t + (1 − α)·cu_{t−1}}</pre>
 * seeded with {@code cu_0 = obs_0}. Each observation is one settled block's per-subtask cost,
 * {@code actual_duration_minutes / imputedSubtaskCount}.
 *
 * <p><b>Cold-start.</b> The EWMA is trusted only once at least
 * {@link LearnedUnitCost#MIN_OBSERVATIONS} valid observations have accrued. Below that the cost
 * rests on the human prior — a uniform split of the task estimate,
 * {@code estimated_minutes / total_subtasks} — flagged {@code coldStart}. With no observations and
 * no usable estimate the cost is null (no signal).
 *
 * <p>Design pattern: Strategy is deliberately avoided — the formula is a fixed domain decision,
 * so a single-algorithm service keeps the invariant in one place (YAGNI). Should a second
 * estimator ever be sanctioned, it moves behind a port then.
 */
public class LearnedUnitCostCalculator {

    /** EWMA smoothing factor (spike #63): weight of the newest observation. */
    static final double ALPHA = 0.3;

    /**
     * Computes the learned unit cost for one task.
     *
     * <p>Observations must already be filtered to valid settled blocks and ordered chronologically
     * by {@code settled_at} (oldest first) — the read port's contract. Invalid observations
     * (no imputed subtask) that slip through are dropped defensively so a divide-by-zero can never
     * corrupt the estimate.
     *
     * @param inputs the task's observations plus its cold-start prior inputs; never null
     * @return the learned or cold-start cost; {@code cu} is null only when no signal exists
     */
    public LearnedUnitCost calculate(TaskCostInputs inputs) {
        List<SettledObservation> valid = inputs.observations().stream()
            .filter(SettledObservation::isValid)
            .toList();

        if (valid.size() < LearnedUnitCost.MIN_OBSERVATIONS) {
            return LearnedUnitCost.coldStart(inputs.taskId(), coldStartPrior(inputs), valid.size());
        }
        return LearnedUnitCost.learned(inputs.taskId(), ewma(valid), valid.size());
    }

    /**
     * Folds the EWMA over the valid observations oldest-first, seeded with the first observation.
     *
     * @param valid non-empty, chronologically ordered valid observations
     * @return the smoothed minutes-per-subtask
     */
    private static double ewma(List<SettledObservation> valid) {
        double cu = valid.get(0).unitCost();
        for (int i = 1; i < valid.size(); i++) {
            cu = ALPHA * valid.get(i).unitCost() + (1 - ALPHA) * cu;
        }
        return cu;
    }

    /**
     * The human prior: a uniform split of the task estimate across its subtasks. Null when the
     * task has no estimate or no subtasks to split it across.
     *
     * @param inputs the task inputs
     * @return {@code estimated_minutes / total_subtasks}, or null when no prior is available
     */
    private static Double coldStartPrior(TaskCostInputs inputs) {
        Integer estimate = inputs.estimatedMinutes();
        if (estimate == null || inputs.totalSubtasks() <= 0) {
            return null;
        }
        return (double) estimate / inputs.totalSubtasks();
    }
}
