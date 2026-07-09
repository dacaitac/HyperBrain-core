package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read/write model of a {@code core_executable} row joined with its
 * {@code core_execution_profile}, covering every attribute owned by the sync pipeline
 * (HU-10/HU-14, ADR-012). Kept separate from {@link CoreExecutable} so the Apple write-back's
 * narrow read model stays untouched.
 *
 * @param id             surrogate key of the executable
 * @param userId         owning user
 * @param parentId       optional parent executable (Notion {@code Parent Task} relation)
 * @param cycleId        optional owning cycle (Notion {@code Cycle} relation)
 * @param name           human-readable name
 * @param description    optional free-text description
 * @param type           executable type ({@code TASK}, {@code HABIT}, {@code LEAD_MEASURE},
 *                       {@code ACTIVITY}, {@code AGENDA}, {@code LEARNING_SESSION})
 * @param status         lifecycle status ({@code TODO}, {@code IN_PROGRESS}, {@code DONE},
 *                       {@code FAILED}, {@code PLANNED}, {@code WAITING})
 * @param priorityScore  normalized priority score in [0, 1]
 * @param urgencyScore   urgency score
 * @param effortScore    estimated effort in [0, 5]
 * @param isImportant    Eisenhower importance flag (Notion {@code Important}; Prioritizer HU-01);
 *                       null is persisted as false (DDL {@code NOT NULL DEFAULT false})
 * @param frequency      habit frequency (Notion {@code Frequency}; Habits epic)
 * @param startTime      optional start timestamp
 * @param endTime        optional end / due timestamp
 * @param sourceCalendar EventKit list or calendar name (Apple authority; never written by Notion)
 * @param energyDrain     execution profile energy drain in [1, 5]
 * @param mentalLoad      execution profile mental load in [1, 5]
 * @param impact          execution profile impact in [1, 8]
 * @param systemGenerated true for internal accounting rows (e.g. focus-switch snapshot subtasks,
 *                        ADR-013 DR-06); such rows are never written back to external systems
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
    Boolean isImportant,
    Double frequency,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    String sourceCalendar,
    Integer energyDrain,
    Integer mentalLoad,
    Integer impact,
    boolean systemGenerated
) {
}
