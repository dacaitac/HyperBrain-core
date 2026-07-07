package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read model of a {@code core_executable} row joined with its {@code core_execution_profile},
 * covering every attribute propagated to the Notion Tasks database (HU-10). Kept separate from
 * {@link CoreExecutable} so the Apple pipeline's narrow write model stays untouched.
 *
 * @param id            surrogate key of the executable
 * @param userId        owning user
 * @param parentId      optional parent executable (Notion {@code Parent Task} relation)
 * @param cycleId       optional owning cycle (Notion {@code Cycle} relation)
 * @param name          human-readable name
 * @param description   optional free-text description
 * @param type          executable type ({@code TASK}, {@code HABIT}, {@code LEAD_MEASURE},
 *                      {@code ACTIVITY}, {@code AGENDA}, {@code LEARNING_SESSION})
 * @param status        lifecycle status ({@code TODO}, {@code IN_PROGRESS}, {@code DONE},
 *                      {@code FAILED}, {@code PLANNED}, {@code WAITING})
 * @param priorityScore normalized priority score in [0, 1]
 * @param urgencyScore  urgency score
 * @param effortScore   estimated effort in [0, 5]
 * @param startTime     optional start timestamp
 * @param endTime       optional end / due timestamp
 * @param energyDrain   execution profile energy drain in [1, 5]
 * @param mentalLoad    execution profile mental load in [1, 5]
 * @param impact        execution profile impact in [1, 8]
 */
public record ExecutableSnapshot(
    UUID id,
    UUID userId,
    UUID parentId,
    UUID cycleId,
    String name,
    String description,
    String type,
    String status,
    Double priorityScore,
    Double urgencyScore,
    Double effortScore,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    Integer energyDrain,
    Integer mentalLoad,
    Integer impact
) {
}
