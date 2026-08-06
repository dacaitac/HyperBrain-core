package com.hyperbrain.sync.domain.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the planner did with an inbound time-block edit (ADR-038): which facets were applied,
 * which were rejected (permanent semantic conflicts, never transport failures), and the
 * side effects the sync layer must propagate to the satellites.
 *
 * @param applied          true when at least one facet changed the persisted state (the origin
 *                         was flipped to {@code USER})
 * @param rejections       human-readable reasons for the rejected facets, mirrored to the page's
 *                         {@code Sync Note}; never null, empty when everything was accepted
 * @param addedMembers     executables that joined the block (due-date re-projection, ADR-035 D-C)
 * @param removedMembers   executables that left the block (due-date re-projection on removal too)
 * @param remirrorBlockIds other blocks whose state changed as a side effect (a D5 move's source
 *                         block) and must be re-mirrored
 * @param deletedBlockIds  blocks a move emptied and the planner deleted (the {@code PersistedBlock}
 *                         non-empty invariant); their satellite counterparts must be removed
 */
public record TimeBlockEditOutcome(
    boolean applied,
    List<String> rejections,
    Set<UUID> addedMembers,
    Set<UUID> removedMembers,
    Set<UUID> remirrorBlockIds,
    Set<UUID> deletedBlockIds
) {

    public TimeBlockEditOutcome {
        rejections = List.copyOf(rejections);
        addedMembers = Set.copyOf(addedMembers);
        removedMembers = Set.copyOf(removedMembers);
        remirrorBlockIds = Set.copyOf(remirrorBlockIds);
        deletedBlockIds = Set.copyOf(deletedBlockIds);
    }

    /** @return an outcome that changed nothing and rejected nothing */
    public static TimeBlockEditOutcome noop() {
        return new TimeBlockEditOutcome(false, List.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** @return true when at least one facet was rejected and the canonical state must be re-asserted */
    public boolean rejected() {
        return !rejections.isEmpty();
    }
}
