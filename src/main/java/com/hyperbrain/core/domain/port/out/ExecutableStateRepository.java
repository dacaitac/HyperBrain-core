package com.hyperbrain.core.domain.port.out;

import com.hyperbrain.core.domain.model.BlockWindow;
import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.model.SubtaskCounts;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the SYSTEM-owned progress, containment and streak accounting of
 * {@code core_executable}: {@code progress}, {@code system_generated}, the containment columns
 * ({@code container_block_id}, {@code container_planned_minutes}, {@code container_ord}), the
 * hard-copied schedule of contained children and the streak pair live outside the ADR-012 authority
 * matrix, so the sync upsert never touches them and these writes survive the ingestion transaction
 * untouched.
 *
 * <p>The focus accounting this port used to carry — the executing focus a switch had to cut, the
 * instant snapshot subtask, the re-estimation flag and the imputation of completed subtasks to a
 * block — is gone with the register itself (ADR-040 D13).
 */
public interface ExecutableStateRepository {

    /**
     * Tells whether an executable is a system-generated row. Missing rows report false (a CREATE
     * ingestion is never an echo of one).
     *
     * @param executableId the executable
     * @return true only for persisted {@code system_generated} rows
     */
    boolean isSystemGenerated(UUID executableId);

    /**
     * Counts the user subtasks of a parent, excluding system-generated snapshots,
     * {@code TIME_BLOCK} children (FOCUS accounting rows are not work items, ADR-039) and,
     * optionally, the row being ingested (whose in-memory state supersedes the persisted one).
     *
     * @param parentId    the parent executable
     * @param excludingId subtask to exclude from the counters; may be null
     * @return the counters feeding the materialized {@code progress}
     */
    SubtaskCounts countUserSubtasks(UUID parentId, UUID excludingId);

    /**
     * Writes the materialized progress of a parent (DR-07).
     *
     * @param executableId the parent
     * @param progress     progress in [0, 1], or null when the parent has no user subtasks
     */
    void updateProgress(UUID executableId, Double progress);

    /**
     * Stamps {@code last_completed_at} on an executable observed closing ({@code DONE} or
     * {@code FAILED}, ADR-039): the sync pipeline does not write that column, so this is the
     * completion clock the intraday-replan guard reads. No-op when the row does not exist yet (a row
     * arriving closed on CREATE is persisted after the rules run).
     *
     * @param executableId the closed executable
     * @param completedAt  observed closure instant
     */
    void markCompleted(UUID executableId, OffsetDateTime completedAt);

    /**
     * Inserts or updates the full attribute set of a {@code core_executable} row plus its
     * {@code core_execution_profile}, keyed by {@code id}. Used by DR-04 to persist
     * recurrence clones generated inside the ingestion transaction.
     *
     * @param snapshot the clone to persist
     */
    void upsertExecutable(ExecutableSnapshot snapshot);

    // ── ADR-039: containment, hard copy and streaks ───────────────────────────

    /**
     * Resolves the scheduling authority of a contained executable, honouring the in-flight merged
     * state before it is persisted: the merged {@code container_block_id} wins, then the persisted
     * row's {@code container_block_id}, then the merged {@code parent_id}. The returned schedule is
     * what the hard-copy rule asserts onto the child (date + cycle are SYSTEM-owned for contained
     * children, ADR-012 D1 as amended by ADR-039).
     *
     * @param executableId      the potentially contained executable (persisted row may not exist
     *                          yet on CREATE)
     * @param mergedContainerId the container id of the merged in-flight state; may be null
     * @param mergedParentId    the parent id of the merged in-flight state; may be null
     * @return the container's schedule, or empty when the executable is not contained
     */
    Optional<ContainerSchedule> findContainerSchedule(UUID executableId, UUID mergedContainerId,
                                                      UUID mergedParentId);

    /**
     * Hard-copies a container's schedule onto every transitively contained descendant
     * (ADR-039: members via {@code container_block_id}, subtasks via {@code parent_id} —
     * the full recursive chain). Idempotent by value: only rows whose values actually differ
     * are touched and reported, so an echo converges to zero writes and zero events. The copy
     * honours DR-01 per child: reminder-backed types receive {@code start_time} only
     * ({@code end_time} cleared); event-backed types receive the full window. A null
     * {@code cycleId} carries no signal and preserves each child's own cycle.
     * {@code TIME_BLOCK} and {@code system_generated} rows never receive copies (containers
     * own their window; snapshots are frozen history).
     *
     * @param containerId the container whose schedule changed
     * @param start       the container's new start instant
     * @param end         the container's new end instant; may be null
     * @param cycleId     the container's cycle, or null to leave children's cycles untouched
     * @return the ids of the descendants whose row actually changed (outbox fan-out unit)
     */
    List<UUID> copyScheduleToContained(UUID containerId, OffsetDateTime start,
                                       OffsetDateTime end, UUID cycleId);

    /**
     * Assigns an executable to a container block (Option B monovalent containment): sets
     * {@code container_block_id}, the member's quota and its order. Overwriting an existing
     * containment IS the move (no dual-membership policy, ADR-039). Reports whether the row
     * actually changed so echoes stay event-free.
     *
     * @param memberId       the executable joining the container
     * @param blockId        the {@code TIME_BLOCK} container
     * @param plannedMinutes the member's quota inside the container; may be null
     * @param ord            the member's order inside the container
     * @return true when the containment columns actually changed
     */
    boolean assignContainer(UUID memberId, UUID blockId, Integer plannedMinutes, int ord);

    /**
     * Hands a planner-authored block over to the user: its {@code origin} becomes {@code USER}, which
     * is the same thing the block Daniel opens himself already says. From that moment the planner
     * stops treating the block as a draft of its own — it is no longer regenerable, so this run and
     * every replan of the day leave its hour and its membership exactly as he arranged them, and the
     * candidate guard keeps its members out of the deal.
     *
     * <p><b>Why the whole block and not the one task.</b> Authority over a block's contents is not
     * something two owners can hold at once (containment is monovalent and the plan is rebuilt whole),
     * and {@code origin} is how every reader already asks the question — "is this mine to re-place?",
     * never "who typed the row". Handing the container over is therefore the existing vocabulary for
     * the answer, and it is what makes the anchor expire on its own: a block belongs to one day, so
     * tomorrow's plan is composed freely from fresh blocks, with nothing accumulated.
     *
     * <p>Guarded to authorship and state ({@code PLANNER} + {@code PLANNED}): a block that is already
     * the user's, already under way or already closed has nothing to hand over, and a {@code FOCUS}
     * accounting row is never touched. The hour is deliberately absent from the guard — a block whose
     * window has already begun still hands over, because he arranges the window he is in — so this is
     * a slightly wider set than the regenerable one, which also demands the block be still ahead.
     *
     * @param blockId the {@code TIME_BLOCK} whose contents the user rearranged; never null
     * @return true when this call handed the block over (false when it was not the planner's to give)
     */
    boolean claimBlockForUser(UUID blockId);

    /**
     * Detaches an executable from its container (clears the containment columns). The
     * hard-copied schedule values PERSIST by design (ADR-039: detach keeps the copied date).
     *
     * @param memberId the executable leaving its container
     * @return true when the row was contained and is now detached
     */
    boolean clearContainer(UUID memberId);

    /**
     * Records a success closure on the streak pair (ADR-039 {@code isAchieved}):
     * {@code current_streak + 1}, {@code best_streak = max(best, current + 1)}.
     *
     * @param executableId the achieved executable
     */
    void recordAchievedStreak(UUID executableId);

    /**
     * Resets {@code current_streak} to zero on a {@code FAILED} closure (ADR-039 matrix:
     * a sanctioned miss resets the streak and never extends it); {@code best_streak} is kept.
     *
     * @param executableId the failed executable
     */
    void resetStreak(UUID executableId);

    /**
     * Copies the streak pair from a closed recurrence onto its DR-04 clone, so the streak
     * survives the never-miss-twice cloning chain.
     *
     * @param sourceId the closed original
     * @param targetId the freshly persisted clone
     */
    void copyStreaks(UUID sourceId, UUID targetId);

    /**
     * Reads a set of executables as snapshots, for callers that need their type and current schedule
     * before deciding what to write — the published containment operation among them (ADR-040 D11).
     * Ids with no row are simply absent from the result.
     *
     * @param ids the executables to read; never null, may be empty
     * @return the snapshots found, in no guaranteed order; never null
     */
    List<ExecutableSnapshot> findAllById(Collection<UUID> ids);

    /**
     * Reads the executables a block currently holds through {@code container_block_id}, ordered by
     * their {@code container_ord}. The release half of ADR-040 D10 walks exactly this set: every member
     * is let go, with its own event, <b>before</b> the block row is deleted.
     *
     * @param blockId the {@code TIME_BLOCK} container; never null
     * @return its members, in container order; never null, may be empty
     */
    List<ExecutableSnapshot> findContainedBy(UUID blockId);

    /**
     * Deletes a planner-authored {@code TIME_BLOCK} the plan withdrew, with every guard <b>inside the
     * predicate</b> (ADR-040 D10): planner origin, still {@code PLANNED}, and no history hanging off it
     * — no frozen duration, no completion clock, no member still contained and nothing imputed to it.
     *
     * <p>Guards in the predicate rather than in a prior read is what makes the withdrawal race-safe:
     * there is no window between checking and deleting. A call that deletes nothing is a notice, not a
     * failure.
     *
     * @param blockId the block to delete
     * @return true when the row was actually deleted
     */
    boolean deleteWithdrawnBlock(UUID blockId);

    // ── ADR-040 D12: the recurrence clone is born with a block of its own day ─

    /**
     * Reads a {@code TIME_BLOCK} executable as a window.
     *
     * @param blockId the block; never null
     * @return its window, or empty when the id is not a block
     */
    Optional<BlockWindow> findBlockWindow(UUID blockId);

    /**
     * Finds the block that realises a given template slot on a given local day — the reuse half of
     * ADR-040 D12. Without it, every recurring occurrence would fabricate a block of its own and the
     * day would be a wall again.
     *
     * @param userId         the owning user; never null
     * @param templateSlotId the slot to look for; never null
     * @param dayStart       the day's start instant (inclusive); never null
     * @param dayEnd         the day's end instant (exclusive); never null
     * @return the block that already holds that band of that day, or empty
     */
    Optional<UUID> findBlockOnDayBySlot(UUID userId, String templateSlotId, OffsetDateTime dayStart,
                                        OffsetDateTime dayEnd);

    /**
     * Inserts a planner-authored {@code TIME_BLOCK} executable. Used when a recurrence clone lands on a
     * day that has no block for its band yet.
     *
     * @param block the window to persist; never null
     */
    void insertBlock(BlockWindow block);

    /**
     * Reads the user's timezone, so a local day can be bounded without leaving this module.
     *
     * @param userId the owning user; never null
     * @return the user's zone; never null
     */
    ZoneId findUserZone(UUID userId);

    // ── ADR-040 D4: the day-close sweep of what expired ───────────────────────

    /**
     * Loads the user's expired candidates: still-open, user-owned rows of a swept type whose window
     * closed <b>before {@code referenceInstant}</b>.
     *
     * <p><b>The bound is an instant, not a day</b> (Daniel, 2026-08-07): the sweep runs on every
     * replan as well as at day close, and it closes everything behind the hour it fired at. A task
     * scheduled for ten this morning and still open when the button is pressed at four in the
     * afternoon is expired — under a day-granular predicate it was not, because its date was today,
     * and that is precisely the work that used to sit stranded until midnight.
     *
     * <p><b>An hourless window expires when its day does.</b> A row whose {@code start_time} is the
     * midnight placeholder of a date carries no hour, so it has not "passed" at four in the
     * afternoon — it is due sometime today. Its window is therefore read as closing at the end of
     * its local day, which is why the zone is still a parameter. Without this the first afternoon
     * replan would fail every habit merely dated for today.
     *
     * <p>Two exclusions are part of the predicate, not accidents. Dateless rows are never
     * candidates — they are the bag the day draws from (ADR-040 D3), and they cannot expire.
     * Rows with a {@code parent_id} are never candidates either: a contained child's date is a
     * SYSTEM-owned hard copy of its container (DR-10), so sweeping it on its own would fight the
     * copy; it moves when its container moves.
     *
     * @param userId           owning user
     * @param types            the {@code core_executable.type} values the sweep acts on
     * @param referenceInstant the instant the sweep fired at; everything closed before it expired
     * @param zone             the timezone an hourless window's day is bounded in
     * @return the expired candidates, oldest window first
     */
    List<ExecutableSnapshot> findOverdue(UUID userId, Collection<String> types,
                                         OffsetDateTime referenceInstant, ZoneId zone);

    /**
     * Closes an expired executable as a sanctioned miss ({@code FAILED}). Conditional on the row
     * still being open, which is what makes the sweep idempotent and race-safe: a second run — or
     * a human who closed the item meanwhile — reports false and the caller performs no side
     * effect (no streak reset, no recurrence clone, no outbox event).
     *
     * @param executableId the expired executable
     * @return true when this call closed the row
     */
    boolean closeAsFailed(UUID executableId);

    /**
     * Rewrites the window of an open executable — the re-dating of a {@code TASK} and the date
     * clearing of a {@code BUYING} share this one write. Idempotent by value ({@code IS DISTINCT
     * FROM} guards) and conditional on the row still being open, so re-running the sweep moves
     * nothing.
     *
     * @param executableId the executable to move
     * @param startTime    the new start instant, or null to clear the date
     * @param endTime      the new end instant, or null
     * @return true when the row actually changed
     */
    boolean reschedule(UUID executableId, OffsetDateTime startTime, OffsetDateTime endTime);
}
