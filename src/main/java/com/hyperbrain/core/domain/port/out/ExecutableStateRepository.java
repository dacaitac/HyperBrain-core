package com.hyperbrain.core.domain.port.out;

import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.model.FocusCandidate;
import com.hyperbrain.core.domain.model.SnapshotSubtask;
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
 * Persistence port for the SYSTEM-owned focus, progress, containment and streak accounting of
 * {@code core_executable} (ADR-013, amended by ADR-039): {@code progress},
 * {@code system_generated}, {@code pending_reestimation}, {@code imputed_block_id}, the
 * containment columns ({@code container_block_id}, {@code container_planned_minutes},
 * {@code container_ord}), the hard-copied schedule of contained children and the streak pair
 * live outside the ADR-012 authority matrix, so the sync upsert never touches them and these
 * writes survive the ingestion transaction untouched.
 */
public interface ExecutableStateRepository {

    /**
     * Finds the user's controllable {@code IN_PROGRESS} executables currently accounted by an
     * executing block — the executing focus a switch must cut (DR-05). {@code TIME_BLOCK} rows
     * are never candidates: a block in {@code IN_PROGRESS} is focus accounting, not a focus
     * that cuts (ADR-039).
     *
     * @param userId      owning user
     * @param excludingId the executable taking the focus; never a cut candidate
     * @return the cut candidates with their original effort labels
     */
    List<FocusCandidate> findActiveFocus(UUID userId, UUID excludingId);

    /**
     * Finds the user's controllable {@code IN_PROGRESS} executables that pre-date the block
     * model entirely (no block rows at all, legacy or new) and are not already awaiting
     * re-estimation ({@code pending_reestimation = false}). Legacy fallback of DR-05:
     * consulted only when {@link #findActiveFocus} returns nothing; their snapshot window is
     * the punctual {@code [now, now]}.
     *
     * @param userId      owning user
     * @param excludingId the executable taking the focus
     * @return the legacy cut candidates
     */
    List<FocusCandidate> findLegacyInProgress(UUID userId, UUID excludingId);

    /**
     * Tells whether an executable is a system-generated focus snapshot. Missing rows report
     * false (a CREATE ingestion is never a snapshot echo).
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
     * Persists a focus snapshot as a completed {@code system_generated} subtask, including its
     * frozen execution-profile labels (DR-06).
     *
     * @param snapshot the snapshot to insert
     */
    void insertSystemSnapshot(SnapshotSubtask snapshot);

    /**
     * Flags a cut task {@code pending_reestimation} without touching its effort values (DR-06).
     *
     * @param executableId the cut task
     */
    void flagPendingReestimation(UUID executableId);

    /**
     * Clears the {@code pending_reestimation} flag once a human source supplies fresh effort
     * values (DR-06 confirmation). Conditional: a no-op unless the flag was set.
     *
     * @param executableId the task being confirmed
     * @return true if the flag was cleared by this call
     */
    boolean clearPendingReestimation(UUID executableId);

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
     * completion clock the settlement imputation and the intraday-replan guard read. No-op
     * when the row does not exist yet (a row arriving closed on CREATE is persisted after the
     * rules run).
     *
     * @param executableId the closed executable
     * @param completedAt  observed closure instant
     */
    void markCompleted(UUID executableId, OffsetDateTime completedAt);

    /**
     * Eagerly imputes one completed subtask to the executing block covering its completion
     * (DR-07), via {@code imputed_block_id} (ADR-039 successor column).
     *
     * @param subtaskId the completed subtask
     * @param blockId   the covering executing {@code TIME_BLOCK} executable
     */
    void imputeToBlock(UUID subtaskId, UUID blockId);

    /**
     * Clears the imputation of a subtask that was un-completed: the block record must not
     * credit work that was taken back.
     *
     * @param subtaskId the reverted subtask
     */
    void clearImputation(UUID subtaskId);

    /**
     * Settlement sweep of DR-08: imputes to a block every user subtask — of the block's FOCUS
     * anchor or of any of its contained members — that closed as {@code DONE} inside the block
     * window and is not yet imputed. {@code FAILED} closures are never imputed (ADR-039
     * matrix: a sanctioned miss earns no execution credit).
     *
     * @param blockId     the {@code TIME_BLOCK} executable being settled
     * @param windowStart block window start
     * @param windowEnd   block window end (settlement instant or {@code end_time})
     * @return how many subtasks were imputed by this sweep
     */
    int imputeCompletedSubtasks(UUID blockId, OffsetDateTime windowStart, OffsetDateTime windowEnd);

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

    // ── ADR-040 D4: the day-close sweep of what expired ───────────────────────

    /**
     * Loads the user's expired candidates for the day-close sweep: still-open, user-owned rows of
     * a swept type whose window ({@code end_time}, falling back to {@code start_time}) lies on a
     * local day <b>strictly before</b> {@code referenceDay}.
     *
     * <p>Two exclusions are part of the predicate, not accidents. Dateless rows are never
     * candidates — they are the bag the day draws from (ADR-040 D3), and they cannot expire.
     * Rows with a {@code parent_id} are never candidates either: a contained child's date is a
     * SYSTEM-owned hard copy of its container (DR-10), so sweeping it on its own would fight the
     * copy; it moves when its container moves.
     *
     * @param userId       owning user
     * @param types        the {@code core_executable.type} values the sweep acts on
     * @param referenceDay the local day the sweep opens; everything before it has expired
     * @param zone         the timezone the local day is reasoned in
     * @return the expired candidates, oldest window first
     */
    List<ExecutableSnapshot> findOverdue(UUID userId, Collection<String> types,
                                         LocalDate referenceDay, ZoneId zone);

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
