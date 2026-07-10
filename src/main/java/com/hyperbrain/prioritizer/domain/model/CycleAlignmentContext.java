package com.hyperbrain.prioritizer.domain.model;

import java.util.List;

/**
 * The structural facts about one {@code core_cycle} that the graded alignment factor needs, gathered
 * by the read port in a single recursive pass so the pure resolver stays free of persistence: the
 * cycle's own horizon type and every {@code ACTIVE} ancestor reachable by walking
 * {@code parent_cycle_id} upward, each tagged with the hop-distance to it.
 *
 * <p>The own type drives the Coach cap ({@code ROUTINE} cannot inherit a high value just by hanging
 * off an MCI); the ancestor links drive {@code max}<sub>c</sub> {@code W(c.type) · δ(d)}. See
 * {@code AlignmentResolver}.
 *
 * @param ownType         the cycle's own horizon type; never null
 * @param activeAncestors the {@code ACTIVE} ancestors (including the cycle itself when it is active),
 *                        each with its hop-distance; never null, may be empty when no active ancestor
 *                        exists on the way up
 */
public record CycleAlignmentContext(CycleType ownType, List<AncestorLink> activeAncestors) {

    public CycleAlignmentContext {
        if (ownType == null) {
            throw new IllegalArgumentException("ownType must not be null");
        }
        activeAncestors = activeAncestors == null ? List.of() : List.copyOf(activeAncestors);
    }

    /**
     * One {@code ACTIVE} ancestor cycle reachable from a source cycle, with the number of
     * {@code parent_cycle_id} hops from the source cycle up to it.
     *
     * @param ancestorType the ancestor cycle's horizon type; never null
     * @param distance     hops from the source cycle up to this ancestor ({@code 0} when the source
     *                     cycle is itself the active ancestor); never negative
     */
    public record AncestorLink(CycleType ancestorType, int distance) {

        public AncestorLink {
            if (ancestorType == null) {
                throw new IllegalArgumentException("ancestorType must not be null");
            }
            if (distance < 0) {
                throw new IllegalArgumentException("distance must be non-negative: " + distance);
            }
        }
    }
}
