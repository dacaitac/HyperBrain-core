package com.hyperbrain.prioritizer.application;

import com.hyperbrain.prioritizer.domain.model.PriorityScore;

import java.util.Optional;

/**
 * Outcome of a single-executable {@link PrioritizerService#rescore(java.util.UUID)}: the recomputed
 * score (absent when the executable carries no priority signal) together with whether persisting it
 * actually moved the stored value.
 *
 * <p>{@code moved} is the Prioritizer's single, authoritative definition of "the score changed": it
 * is the exact boolean the persistence layer derives from its epsilon-guarded diff
 * ({@code JdbcPriorityStateRepository.persistIfChanged}). Callers that must reflect a score change
 * outward (the on-event Notion reflection, #66a) key off this flag rather than re-deriving a second
 * notion of change — there is one definition of moved, and it lives in the Prioritizer.
 *
 * @param score the recomputed score, or empty when the executable has no priority to compute
 *              (not persisted yet, system-generated, or a read-only AGENDA row)
 * @param moved true iff the recomputed score differed from the stored one beyond the epsilon and was
 *              therefore persisted; always false when {@code score} is empty
 */
public record RescoreResult(Optional<PriorityScore> score, boolean moved) {

    /** A no-op outcome: the executable carried no priority signal, so nothing was scored or moved. */
    public static RescoreResult noSignal() {
        return new RescoreResult(Optional.empty(), false);
    }

    /**
     * A scored outcome.
     *
     * @param score the recomputed score (never null)
     * @param moved whether persisting it changed the stored value
     * @return the outcome carrying the score and its moved flag
     */
    public static RescoreResult scored(PriorityScore score, boolean moved) {
        return new RescoreResult(Optional.of(score), moved);
    }
}
