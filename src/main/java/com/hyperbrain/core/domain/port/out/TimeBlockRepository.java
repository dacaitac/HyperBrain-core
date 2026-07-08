package com.hyperbrain.core.domain.port.out;

import com.hyperbrain.core.domain.model.TimeBlock;
import com.hyperbrain.core.domain.model.TimeBlockStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code core_time_block} rows (ADR-013 D1/D5). Settlement is idempotent
 * and race-safe: the focus-switch rule and the expiry scheduler may compete for the same block,
 * which {@link #lockOpenExpired} ({@code FOR UPDATE SKIP LOCKED}) and the conditional
 * {@link #settle} resolve without double settlement.
 */
public interface TimeBlockRepository {

    /**
     * Finds the executing block of an executable, if any. By construction (DR-05) an
     * executable holds at most one {@code ACTIVE} block; if legacy data breaks that, the most
     * recently started one is returned.
     *
     * @param executableId the executable
     * @return its ACTIVE block, or empty
     */
    Optional<TimeBlock> findActiveBlock(UUID executableId);

    /**
     * Inserts a new block row.
     *
     * @param block the block to persist
     */
    void insert(TimeBlock block);

    /**
     * Locks and returns the open blocks whose {@code date_end} already passed, skipping rows
     * locked by a concurrent settlement ({@code FOR UPDATE SKIP LOCKED}). Must run inside the
     * caller's transaction.
     *
     * @param now the expiry boundary
     * @return the due open blocks, oldest first
     */
    List<TimeBlock> lockOpenExpired(OffsetDateTime now);

    /**
     * Settles a block, freezing its outcome. Conditional on the block still being open, so a
     * lost race is reported instead of overwriting a previous settlement.
     *
     * @param blockId               the block to settle
     * @param finalStatus           {@link TimeBlockStatus#SETTLED} (focus switch) or
     *                              {@link TimeBlockStatus#EXPIRED} (expiry)
     * @param actualDurationMinutes gross executed minutes; null when nothing was executed
     * @param settledAt             settlement instant
     * @return true if this call settled the block; false if it was no longer open
     */
    boolean settle(UUID blockId, TimeBlockStatus finalStatus, Integer actualDurationMinutes,
                   OffsetDateTime settledAt);
}
