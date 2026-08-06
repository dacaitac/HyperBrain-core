package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.TimeBlockEditOutcome;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockUserEdit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port through which the inbound Notion Time Blocks sync (ADR-038) reads and mutates
 * the {@code core_time_block} aggregate without the {@code sync} module reaching into
 * {@code planner}. Exact precedent: {@link PlannerBlockDeletionPort} — {@code sync} declares the
 * capability, {@code planner.infrastructure} provides the adapter, so the compile dependency
 * stays {@code planner → sync} and the ownership of block semantics never leaves the planner.
 *
 * <p>All mutations run inside the caller's ingestion transaction and enforce the planner's own
 * invariants: the D5 one-live-block-per-executable-per-day index (move semantics, TOCTOU race
 * caught as a rejection), WIG atomicity (ADR-027 D4), the planner walls (read-only AGENDA windows
 * and block-vs-block overlap), and the {@code PersistedBlock} non-empty invariant (a move that
 * empties a live block deletes it). Rejections are reported in the outcome — they are permanent
 * semantic conflicts, never exceptions.
 */
public interface PlannerTimeBlockPort {

    /**
     * Loads the sync-facing snapshot of one block with its membership, any status or origin.
     *
     * @param blockId the {@code core_time_block.id}
     * @return the snapshot, or empty when the row no longer exists
     */
    Optional<TimeBlockSnapshot> findBlock(UUID blockId);

    /**
     * Re-reads the day's live mirrorable blocks ({@code PLANNED}/{@code ACTIVE}, origin
     * {@code PLANNER} or {@code USER} — FOCUS excluded) for the day-scoped Notion mirror pass.
     *
     * @param userId   the owning user
     * @param dayStart the local day's start instant (inclusive)
     * @param dayEnd   the local day's end instant (exclusive)
     * @return the blocks with their members, chronological; never null
     */
    List<TimeBlockSnapshot> findLiveBlocksForDay(UUID userId, OffsetDateTime dayStart,
                                                 OffsetDateTime dayEnd);

    /**
     * Reads every block the one-shot backfill must mirror: live blocks plus {@code SETTLED}
     * ones settled after the cutoff; {@code EXPIRED} and FOCUS blocks are never backfilled.
     *
     * @param settledAfter the oldest settlement instant still mirrored
     * @return the mirrorable blocks; never null
     */
    List<TimeBlockSnapshot> findMirrorableBlocks(OffsetDateTime settledAfter);

    /**
     * Reads the ids of terminal blocks whose settlement is older than the cutoff — the
     * retention sweep candidates. The sweep itself never touches these rows; it only clears
     * their Notion side.
     *
     * @param settledBefore the retention boundary
     * @return the candidate block ids; never null
     */
    List<UUID> findTerminalBlockIdsSettledBefore(OffsetDateTime settledBefore);

    /**
     * Applies a user's Notion edit to a live block: retime/retheme, and a membership diff with
     * D5 move semantics (delete-before-insert in the same transaction, attribute preservation
     * for surviving members, {@code ord = max + 1} and a default minute share for the added
     * ones). Flips {@code origin = 'USER'} iff something was actually applied — the wall
     * {@code reconcilePlannedBlocks} already respects.
     *
     * @param edit the effective diff to apply; facets left null are not touched
     * @return what was applied, rejected and the side effects to propagate
     */
    TimeBlockEditOutcome applyUserEdit(TimeBlockUserEdit edit);

    /**
     * Creates a {@code PLANNED}/{@code USER} block from a page born in Notion (ADR-038 rule:
     * relation-only, never creates executables). Members join through the same D5/WIG guards as
     * {@link #applyUserEdit}; the block is only created when at least one member survives them
     * (the anchor column requires one).
     *
     * @param userId  the owning user
     * @param blockId the surrogate id to persist the block under (minted by the caller so the
     *                {@code sync_mapping} can be staged in the same transaction)
     * @param start   window start; never null
     * @param end     window end; never null, after {@code start}
     * @param theme   the block's theme extracted from the page title; may be null
     * @param members the mapped member executables in the page's relation order; never empty
     * @return the outcome; {@code applied = false} when no member survived and nothing was created
     */
    TimeBlockEditOutcome createUserBlock(UUID userId, UUID blockId, OffsetDateTime start,
                                         OffsetDateTime end, String theme, List<UUID> members);

    /**
     * De-schedules a live block whose Notion page was archived: deletes the {@code PLANNED} row
     * (origin {@code PLANNER} or {@code USER} — never {@code FOCUS}/{@code ACTIVE} work, which
     * carries telemetry). Idempotent: a missing or non-eligible block is a no-op.
     *
     * @param blockId the block to delete
     * @return true if a row was deleted
     */
    boolean deleteLiveBlock(UUID blockId);
}
