package com.hyperbrain.prioritizer.domain.model;

import java.util.UUID;

/**
 * The raw, un-normalized domain inputs for one executable that feed the Priority Score (v2).
 * Gathered by the read port straight from the aggregate state ({@code core_executable} joined with
 * {@code core_execution_profile}), so the pure {@code PriorityScoreCalculator} stays free of
 * persistence and normalizes on its own.
 *
 * <p>Every factor is carried on its <em>source</em> scale; normalization to {@code [0, 1]} is the
 * calculator's job (the central fix of v2 — see the engine doc). Nullable factors model missing
 * profile data: a task with no impact/effort recorded still gets a score from the factors it has.
 *
 * @param executableId the {@code core_executable} this row scores; never null
 * @param cycleId      the owning cycle ({@code core_executable.cycle_id}); null when unassigned.
 *                     Used only to resolve alignment against the active MCI cycles, never normalized.
 * @param impact       {@code core_execution_profile.impact} on the Fibonacci 1–8 scale; null when
 *                     the execution profile has no impact recorded
 * @param urgencyRaw   the domain-computed urgency on its 0–6 source scale, anchored to a fixed
 *                     deadline horizon (0 while the deadline is beyond the horizon, 5 at the due
 *                     date, up to 6 when overdue — the port derives and caps this)
 * @param effort       {@code core_executable.effort_score} on the 0–5 scale; null when unestimated
 */
public record ExecutableFactors(
    UUID executableId,
    UUID cycleId,
    Integer impact,
    double urgencyRaw,
    Double effort
) {

    public ExecutableFactors {
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
    }
}
