package com.hyperbrain.prioritizer.application;

import com.hyperbrain.prioritizer.domain.model.PriorityScore;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Published application interface of the {@code prioritizer} module (#66a). It is the <b>only</b>
 * seam through which other modules (notably {@code core}'s domain-rule chain) drive priority
 * recomputation: the {@code core → prioritizer} edge goes exclusively through these two methods, so
 * the modules stay isolated (no ArchUnit yet — this boundary is enforced by convention and review).
 *
 * <p>Both methods run inside the caller's transaction and persist through the same score columns
 * ({@code priority_score}, {@code urgency_score}, {@code priority_computed_at}), writing only the
 * rows whose score actually moved.
 */
public interface PrioritizerService {

    /**
     * Recomputes the full Priority Score (v2) of a single executable — impact, urgency evaluated at
     * {@code now()}, inverted effort and graded alignment — and persists it when it changed.
     *
     * <p>Used by the on-event recompute during ingestion ({@code PriorityRecalculationRule}): the
     * returned score lets the rule rewrite the merged snapshot so the outbound mirror (Notion
     * {@code Priority Score} / {@code Urgence}) reflects the fresh value. Returns empty when the
     * executable carries no priority signal (not yet persisted, system-generated, or a read-only
     * AGENDA row), in which case there is nothing to reflect.
     *
     * @param executableId the executable to rescore
     * @return the recomputed score, or empty when the executable has no priority to compute
     */
    Optional<PriorityScore> rescore(UUID executableId);

    /**
     * Reprioritizes the user's whole day: scores and ranks every pending executable and persists the
     * results, returning the ids whose score changed so the caller can propagate only those.
     *
     * @param userId the user whose day to reprioritize
     * @return the ids of the executables whose persisted score changed; never null, may be empty
     */
    Set<UUID> reprioritizeToday(UUID userId);
}
