package com.hyperbrain.prioritizer.domain.model;

import java.util.UUID;

/**
 * The computed Priority Score (v2) of one executable: {@code P ∈ [0, 1]}, plus the inverted-effort
 * factor kept alongside it as the deterministic tie-breaker (Quick Wins win ties). Persisted into
 * {@code core_executable.priority_score}.
 *
 * @param executableId the scored {@code core_executable}
 * @param score        the weighted, normalized Priority Score in {@code [0, 1]}
 * @param effortInv    the normalized inverted-effort factor {@code (5 − effort) / 5} in {@code [0, 1]};
 *                     retained so the ranking can break score ties without recomputing it
 */
public record PriorityScore(UUID executableId, double score, double effortInv) {
}
