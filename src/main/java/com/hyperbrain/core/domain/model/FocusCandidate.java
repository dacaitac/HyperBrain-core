package com.hyperbrain.core.domain.model;

import java.util.UUID;

/**
 * A controllable {@code IN_PROGRESS} executable eligible to be cut by a focus switch
 * (DR-05/DR-06, ADR-013 D3), carrying the original effort labels the snapshot subtask must
 * freeze before they are emptied for re-estimation.
 *
 * @param id               executable to cut
 * @param userId           owning user
 * @param name             executable name, reused for the snapshot subtask
 * @param effortScore      original effort in [0, 5]; may be null
 * @param isImportant      original Eisenhower flag
 * @param energyDrain      original execution-profile energy drain; may be null
 * @param mentalLoad       original execution-profile mental load; may be null
 * @param impact           original execution-profile impact; may be null
 * @param estimatedMinutes original execution-profile estimate; may be null
 */
public record FocusCandidate(
    UUID id,
    UUID userId,
    String name,
    Double effortScore,
    Boolean isImportant,
    Integer energyDrain,
    Integer mentalLoad,
    Integer impact,
    Integer estimatedMinutes
) {
}
