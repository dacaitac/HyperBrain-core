package com.hyperbrain.core.application.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code TimeBlockSettledEvent} (events-v1.yaml v1.5.0, DR-08).
 *
 * @param blockId               the settled block
 * @param executableId          the block's executable
 * @param finalStatus           SETTLED (focus switch) or EXPIRED (expiry scheduler)
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
