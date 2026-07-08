package com.hyperbrain.core.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One {@code core_time_block} row: the Planner's projection unit (ADR-013 D1). The task keeps
 * state and effort; the <i>when</i> lives here. A task may hold N blocks over its life; expired
 * or interrupted blocks are settled, never stretched.
 *
 * @param id                    surrogate key
 * @param executableId          owning executable
 * @param dateStart             when the block starts (or started)
 * @param dateEnd               planned end; null for auto-opened FOCUS blocks (settled by the
 *                              next focus switch, never by expiry)
 * @param status                lifecycle state
 * @param origin                who opened the block
 * @param plannedMinutes        planned duration; counterpart of {@code actualDurationMinutes}
 *                              for the estimation feedback loop (#52)
 * @param actualDurationMinutes gross executed minutes, frozen on settlement (AGENDA clock
 *                              suspension is deferred to HU-02)
 * @param settledAt             settlement instant; null while the block is open
 * @param createdAt             row creation instant
 */
public record TimeBlock(
    UUID id,
    UUID executableId,
    OffsetDateTime dateStart,
    OffsetDateTime dateEnd,
    TimeBlockStatus status,
    TimeBlockOrigin origin,
    Integer plannedMinutes,
    Integer actualDurationMinutes,
    OffsetDateTime settledAt,
    OffsetDateTime createdAt
) {
}
