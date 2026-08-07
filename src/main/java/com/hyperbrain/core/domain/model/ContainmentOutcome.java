package com.hyperbrain.core.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * What one call to the published containment operation actually changed.
 *
 * <p>The distinction between {@code changed} and {@code rejected} is what lets a caller stay quiet:
 * a replan that re-offers the same membership changes nothing and emits nothing, which is exactly the
 * write-noise suppression ADR-040 D17 demands.
 *
 * @param contained the members whose containment columns actually moved; never null
 * @param recopied  the rows — members and their descendants — whose hard-copied date or cycle actually
 *                  moved, each of which got its own event; never null
 * @param rejected  the members refused by the eligibility rule (they already own a window of their
 *                  own); never null
 */
public record ContainmentOutcome(List<UUID> contained, List<UUID> recopied, List<UUID> rejected) {

    public ContainmentOutcome {
        contained = List.copyOf(contained);
        recopied = List.copyOf(recopied);
        rejected = List.copyOf(rejected);
    }

    /** An outcome in which nothing at all happened. */
    public static ContainmentOutcome empty() {
        return new ContainmentOutcome(List.of(), List.of(), List.of());
    }

    /** @return true when neither containment nor the hard copy moved a single row */
    public boolean isNoOp() {
        return contained.isEmpty() && recopied.isEmpty();
    }
}
