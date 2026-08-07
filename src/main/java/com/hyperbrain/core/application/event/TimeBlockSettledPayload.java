package com.hyperbrain.core.application.event;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code TimeBlockSettledEvent} (events-v1.yaml, DR-08). Since ADR-039 the block is
 * a {@code TIME_BLOCK} executable; the payload shape is unchanged.
 *
 * <p>Both settlement paths now terminate in {@code DONE} (ADR-040 D4): a block is a container of
 * time that elapsed, never a commitment that was broken, so expiry no longer marks it
 * {@code FAILED}. {@code FAILED} stays in the wire enum because settlements written before that
 * change carry it.
 *
 * @param blockId               the settled block ({@code core_executable.id})
 * @param executableId          the task a FOCUS block accounts for; null for containers
 * @param finalStatus           DONE for both the focus switch and the expiry sweep; FAILED only
 *                              on settlements predating ADR-040
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
