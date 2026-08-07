package com.hyperbrain.core.domain.port.out;

import com.hyperbrain.core.domain.model.TimeBlockExecutable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the lifecycle of {@code TIME_BLOCK} executables (ADR-039). Closing an elapsed
 * block is idempotent and race-safe: {@link #lockOpenExpired} ({@code FOR UPDATE SKIP LOCKED}) and the
 * conditional {@link #settle} mean a block closes exactly once even if two runs overlap.
 *
 * <p>The two reads the focus register needed — finding the block currently accounting for a task, and
 * opening one on the fly — are gone with it (ADR-040 D13). Blocks are authored by the planner now, and
 * by the recurrence clone; nothing opens one as a side effect of a task starting.
 */
public interface TimeBlockExecutableRepository {

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
     * Closes a block: writes its terminal status and stamps {@code last_completed_at}, the clock the
     * intraday-replan guard reads. Conditional on the block still being open, so a lost race is
     * reported instead of overwriting a closure that already happened.
     *
     * <p>{@code actualDurationMinutes} survives as a parameter but nothing passes a value any more:
     * freezing the minutes really spent was the focus register's job, and the series it produced had
     * no consumer left once time estimation was retired (ADR-040 D13). The column stays for the
     * history production already holds.
     *
     * @param blockId               the block to close
     * @param finalStatus           {@link TimeBlockExecutable#STATUS_DONE} or
     *                              {@link TimeBlockExecutable#STATUS_FAILED}
     * @param actualDurationMinutes legacy executed minutes; always null on the live path
     * @param settledAt             closure instant
     * @return true if this call closed the block; false if it was no longer open
     */
    boolean settle(UUID blockId, String finalStatus, Integer actualDurationMinutes,
                   OffsetDateTime settledAt);
}
