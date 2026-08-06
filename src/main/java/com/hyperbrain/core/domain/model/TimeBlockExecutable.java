package com.hyperbrain.core.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A {@code core_executable} row of type {@code TIME_BLOCK} (ADR-039: the executable IS the
 * block). The former {@code core_time_block} aggregate collapsed into the executable table:
 * {@code theme → name}, {@code reason → description}, {@code date_start/date_end →
 * start_time/end_time}, {@code settled_at → last_completed_at}, and the lifecycle
 * {@code PLANNED → PLANNED}, {@code ACTIVE → IN_PROGRESS}, {@code SETTLED → DONE},
 * {@code EXPIRED → FAILED}.
 *
 * <p>Containment is inverted with respect to the legacy model: members point at the block via
 * {@code container_block_id}; the only block-side anchor left is {@code parent_id}, used by
 * FOCUS blocks to ride under the task they account for (cascade-deleted with it, exactly like
 * the legacy {@code executable_id} FK).
 *
 * @param id                    the block's {@code core_executable.id}
 * @param userId                owning user
 * @param anchorTaskId          the task a FOCUS block accounts for ({@code parent_id}); null
 *                              for PLANNER/USER containers
 * @param startTime             block window start; never null
 * @param endTime               planned end; null for auto-opened FOCUS blocks (settled by the
 *                              next focus switch, never by expiry)
 * @param status                lifecycle state ({@code PLANNED}, {@code IN_PROGRESS},
 *                              {@code DONE}, {@code FAILED})
 * @param origin                who opened the block ({@code PLANNER}, {@code FOCUS}, {@code USER})
 * @param actualDurationMinutes gross executed minutes, frozen on settlement (DR-08)
 * @param lastCompletedAt       settlement instant; null while the block is open
 */
public record TimeBlockExecutable(
    UUID id,
    UUID userId,
    UUID anchorTaskId,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    String status,
    String origin,
    Integer actualDurationMinutes,
    OffsetDateTime lastCompletedAt
) {

    /** Lifecycle value of a block reserved by the Planner or just opened (not yet executing). */
    public static final String STATUS_PLANNED = "PLANNED";
    /** Lifecycle value of the executing block (legacy {@code ACTIVE}). */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** Lifecycle value of a block settled by a focus switch (legacy {@code SETTLED}). */
    public static final String STATUS_DONE = "DONE";
    /** Lifecycle value of a block settled by the expiry sweep (legacy {@code EXPIRED}). */
    public static final String STATUS_FAILED = "FAILED";

    /** Origin of blocks materialized by the Planner engine (HU-01). */
    public static final String ORIGIN_PLANNER = "PLANNER";
    /** Origin of blocks auto-opened by the single-focus rule (DR-05); never mirrored. */
    public static final String ORIGIN_FOCUS = "FOCUS";
    /** Origin of blocks created or edited by the user (Notion inbound). */
    public static final String ORIGIN_USER = "USER";

    /** @return true while the block can still be settled ({@code PLANNED} or {@code IN_PROGRESS}) */
    public boolean open() {
        return STATUS_PLANNED.equals(status) || STATUS_IN_PROGRESS.equals(status);
    }

    /** @return the planned duration derived from the window, or null for open-ended FOCUS blocks */
    public Integer plannedMinutes() {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return null;
        }
        return (int) Duration.between(startTime, endTime).toMinutes();
    }
}
