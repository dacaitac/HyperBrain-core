package com.hyperbrain.core.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable record of the executed stretch of a task cut by a focus switch (DR-06,
 * ADR-013 D3): persisted as a completed {@code system_generated} subtask under the cut task,
 * freezing the original effort labels and the executed window. Each snapshot accumulates one
 * (original estimate, observed reality) pair for the estimation feedback loop (#52).
 *
 * @param id               new executable id of the snapshot
 * @param userId           owning user
 * @param parentId         the cut task
 * @param name             snapshot name (the cut task's name)
 * @param description      executed-window line, format
 *                         {@code [focus] <start ISO-8601> -> <cut ISO-8601> (<minutes> min)}
 * @param effortScore      frozen original effort
 * @param isImportant      frozen original Eisenhower flag
 * @param energyDrain      frozen original energy drain
 * @param mentalLoad       frozen original mental load
 * @param impact           frozen original impact
 * @param estimatedMinutes frozen original estimate
 * @param windowStart      start of the executed window (open block start, or the cut instant
 *                         for the legacy blockless case)
 * @param completedAt      the cut instant
 */
public record SnapshotSubtask(
    UUID id,
    UUID userId,
    UUID parentId,
    String name,
    String description,
    Double effortScore,
    Boolean isImportant,
    Integer energyDrain,
    Integer mentalLoad,
    Integer impact,
    Integer estimatedMinutes,
    OffsetDateTime windowStart,
    OffsetDateTime completedAt
) {
}
