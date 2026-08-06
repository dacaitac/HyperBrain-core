package com.hyperbrain.core.application.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code TimeBlockSettledEvent} (events-v1.yaml, DR-08). Since ADR-039 the block is
 * a {@code TIME_BLOCK} executable and the terminal statuses are {@code DONE} (focus switch)
 * and {@code FAILED} (expiry sweep); the payload shape is unchanged.
 *
 * @param blockId               the settled block ({@code core_executable.id})
 * @param executableId          the task a FOCUS block accounts for; null for containers
 * @param finalStatus           DONE (focus switch) or FAILED (expiry scheduler)
 * @param dateStart             block window start
 * @param dateEnd               planned end; null for FOCUS blocks
 * @param plannedMinutes        planned duration; may be null
 * @param actualDurationMinutes gross executed minutes; null when nothing was executed
 * @param imputedSubtaskCount   user subtasks imputed to the block on settlement
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TimeBlockSettledPayload(
    UUID blockId,
    UUID executableId,
    String finalStatus,
    OffsetDateTime dateStart,
    OffsetDateTime dateEnd,
    Integer plannedMinutes,
    Integer actualDurationMinutes,
    Integer imputedSubtaskCount
) {
}
