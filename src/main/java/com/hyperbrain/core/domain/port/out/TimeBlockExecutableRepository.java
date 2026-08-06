package com.hyperbrain.core.domain.port.out;

import com.hyperbrain.core.domain.model.TimeBlockExecutable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code TIME_BLOCK} executables (ADR-039, successor of the legacy
 * {@code core_time_block} repository). Settlement is idempotent and race-safe: the focus-switch
 * rule and the expiry scheduler may compete for the same block, which {@link #lockOpenExpired}
 * ({@code FOR UPDATE SKIP LOCKED}) and the conditional {@link #settle} resolve without double
 * settlement.
 */
public interface TimeBlockExecutableRepository {

    /**
     * Finds the executing block accounting for a task, if any: an {@code IN_PROGRESS}
     * {@code TIME_BLOCK} that either rides under the task as its FOCUS child
     * ({@code parent_id}) or currently contains it ({@code container_block_id}). By
     * construction (DR-05) a task holds at most one; if data breaks that, the most recently
     * started one is returned.
     *
     * @param taskId the task
     * @return its executing block, or empty
     */
    Optional<TimeBlockExecutable> findActiveBlockFor(UUID taskId);

    /**
     * Inserts a new {@code TIME_BLOCK} executable row. FOCUS blocks carry the accounted task
     * as {@code parent_id} and are excluded from every mirror by {@code origin}.
     *
     * @param block the block to persist
     * @param name  the block's display name ({@code core_executable.name} is NOT NULL)
     */
    void insert(TimeBlockExecutable block, String name);

    /**
     * Locks and returns the open blocks whose {@code end_time} already passed, skipping rows
     * locked by a concurrent settlement ({@code FOR UPDATE SKIP LOCKED}). Must run inside the
     * caller's transaction.
     *
     * @param now the expiry boundary
     * @return the due open blocks, oldest first
     */
    List<TimeBlockExecutable> lockOpenExpired(OffsetDateTime now);

    /**
     * Settles a block, freezing its outcome: writes the terminal status ({@code DONE} on a
     * focus switch, {@code FAILED} on expiry), {@code actual_duration_minutes} and stamps
     * {@code last_completed_at}. Conditional on the block still being open, so a lost race is
     * reported instead of overwriting a previous settlement.
     *
     * @param blockId               the block to settle
     * @param finalStatus           {@link TimeBlockExecutable#STATUS_DONE} or
     *                              {@link TimeBlockExecutable#STATUS_FAILED}
     * @param actualDurationMinutes gross executed minutes; null when nothing was executed
     * @param settledAt             settlement instant
     * @return true if this call settled the block; false if it was no longer open
     */
    boolean settle(UUID blockId, String finalStatus, Integer actualDurationMinutes,
                   OffsetDateTime settledAt);
}
