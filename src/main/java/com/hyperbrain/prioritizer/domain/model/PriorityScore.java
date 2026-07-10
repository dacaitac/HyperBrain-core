package com.hyperbrain.prioritizer.domain.model;

import java.util.UUID;

/**
 * The computed Priority Score (v2) of one executable: {@code P ∈ [0, 1]}, plus the raw urgency and
 * the inverted-effort factor kept alongside it. {@code score} is persisted into
 * {@code core_executable.priority_score}; {@code urgency} is persisted <b>raw</b> (0–6, the source
 * scale) into {@code core_executable.urgency_score} and mirrored to Notion's {@code Urgence} (#66a) —
 * it is not normalized. {@code effortInv} is the deterministic tie-breaker (Quick Wins win ties) and
 * is not persisted.
 *
 * @param executableId the scored {@code core_executable}
 * @param score        the weighted, normalized Priority Score in {@code [0, 1]}
 * @param urgency      the raw urgency on its 0–6 source scale (capped at the domain cap 6), reflected
 *                     as-is to {@code urgency_score} / Notion {@code Urgence}
 * @param effortInv    the normalized inverted-effort factor {@code (5 − effort) / 5} in {@code [0, 1]};
 *                     retained so the ranking can break score ties without recomputing it
 */
public record PriorityScore(UUID executableId, double score, double urgency, double effortInv) {
}
