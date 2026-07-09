package com.hyperbrain.core.domain.port.out;

import com.hyperbrain.core.domain.model.FocusCandidate;
import com.hyperbrain.core.domain.model.SnapshotSubtask;
import com.hyperbrain.core.domain.model.SubtaskCounts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the SYSTEM-owned focus & progress accounting of {@code core_executable}
 * (ADR-013): {@code progress}, {@code system_generated}, {@code pending_reestimation} and
 * {@code imputed_time_block_id} live outside the ADR-012 authority matrix, so the sync upsert
 * never touches them and these writes survive the ingestion transaction untouched.
 */
public interface ExecutableStateRepository {

    /**
     * Finds the user's controllable {@code IN_PROGRESS} executables currently holding an
     * {@code ACTIVE} block — the executing focus a switch must cut (DR-05).
     *
     * @param userId      owning user
     * @param excludingId the executable taking the focus; never a cut candidate
     * @return the cut candidates with their original effort labels
     */
    List<FocusCandidate> findActiveFocus(UUID userId, UUID excludingId);

    /**
     * Finds the user's controllable {@code IN_PROGRESS} executables that pre-date the block
     * model entirely (no {@code core_time_block} rows at all) and are not already awaiting
     * re-estimation ({@code pending_reestimation = false}) — a task already flagged was cut once
     * and must not be re-cut into a redundant zero-duration snapshot. The flag stays a soft
     * exclusion here, not a hard degradation: it only prevents a second cut, never hides or
     * downranks the task elsewhere. Legacy fallback of DR-05: consulted only when
     * {@link #findActiveFocus} returns nothing; their snapshot window is the punctual
     * {@code [now, now]}.
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
     * Counts the user subtasks of a parent, excluding system-generated snapshots and,
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
     * Flags a cut task {@code pending_reestimation} without touching its effort values: the
     * remaining work is no longer the one that was estimated (DR-06), but the last known effort
     * (executable {@code effort_score} plus profile {@code energy_drain}, {@code mental_load},
     * {@code impact}, {@code estimated_minutes}) is preserved. The flag is a soft hint for the
     * user to re-estimate, never a data-destroying operation: the full-mirror propagators keep
     * echoing the preserved values to the satellites, so a cut never erases the user's labels.
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
     * Stamps {@code last_completed_at} on a subtask observed transitioning to {@code DONE}
     * (DR-07): the sync pipeline does not write that column, so this is the completion clock
     * the settlement imputation reads. No-op when the row does not exist yet (a subtask
     * arriving as DONE on CREATE is persisted after the rules run).
     *
     * @param executableId the completed subtask
     * @param completedAt  observed completion instant
     */
    void markCompleted(UUID executableId, OffsetDateTime completedAt);

    /**
     * Eagerly imputes one completed subtask to the block covering its completion (DR-07).
     *
     * @param subtaskId the completed subtask
     * @param blockId   the covering open block of the parent
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
     * Settlement sweep of DR-08: imputes to a block every user subtask of its executable
     * completed inside the block window and not yet imputed.
     *
     * @param blockId      the block being settled
     * @param executableId the block's executable (parent of the subtasks)
     * @param windowStart  block window start
     * @param windowEnd    block window end (settlement instant or {@code date_end})
     * @return how many subtasks were imputed by this sweep
     */
    int imputeCompletedSubtasks(UUID blockId, UUID executableId,
                                OffsetDateTime windowStart, OffsetDateTime windowEnd);
}
