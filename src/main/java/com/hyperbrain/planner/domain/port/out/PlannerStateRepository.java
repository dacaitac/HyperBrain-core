package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.MovableCommitment;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.model.PlannerBlockIdentity;
import com.hyperbrain.planner.domain.model.PlannerBlockRow;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.model.SleepSession;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Out-port through which the deterministic agenda floor (#6a) reads the day's domain state directly
 * from the aggregates ({@code core_executable}, {@code core_cycle}, {@code tel_sleep_record},
 * {@code sys_user.settings}) and persists the resulting {@code PLANNED} blocks — which since ADR-039
 * are {@code core_executable} rows of type {@code TIME_BLOCK}, not rows of the frozen
 * {@code core_time_block} table. The floor does <b>not</b> consume the UserStateReadModel
 * (#61) — that is the LLM tier's contract (#6b), not the floor's (Daniel, 2026-07-09).
 *
 * <p>The implementation lives in {@code planner.infrastructure} (JDBC), keeping the domain services
 * free of persistence. All reads are per user and, where relevant, anchored to the target day and the
 * user's timezone.
 */
public interface PlannerStateRepository {

    /**
     * Reads the user's timezone ({@code sys_user.timezone}) as a {@link ZoneId}. User commands
     * (HU-01b slice 2) resolve the local day and the sleep-day bounds through this instead of a
     * hardcoded zone.
     *
     * @param userId the owning user; never null
     * @return the user's zone; never null
     */
    ZoneId loadUserZone(UUID userId);

    /**
     * Gathers the sleep-frontier samples: the local wake and bedtime times of day over the history
     * window, already filtered by the freshness guard, plus the {@code planner_constraints.sleep_window}
     * cold-start fallback from settings. The adapter converts each record's instants into local times
     * of day in the user's timezone so the circular median is a pure computation.
     *
     * @param userId the owning user
     * @param now    the reference instant bounding the history window and the freshness guard
     * @return the wake/bedtime samples and the fallback window; never null
     */
    SleepFrontierInputs loadSleepFrontierInputs(UUID userId, OffsetDateTime now);

    /**
     * Reads last night's {@code sleep_score} for the energy resolution, or null when there is no
     * record within the freshness bound (steering the resolver to the NEUTRAL default).
     *
     * @param userId the owning user
     * @param now    the reference instant for the freshness guard
     * @return the fresh {@code sleep_score}, or null when absent or stale
     */
    Integer loadLastNightSleepScore(UUID userId, OffsetDateTime now);

    /**
     * Reads the sleep the user has actually had in the last day — every session, night and naps
     * alike, in chronological order. This is the day's answer to <em>when</em> he slept, which the
     * score alone cannot give: an afternoon nap that ended at 13:56 says something about 14:00 that no
     * number does.
     *
     * @param userId the owning user
     * @param now    the reference instant the last-day lookback is measured from
     * @return the sessions that ended within the lookback, chronological; empty when there are none
     */
    List<SleepSession> loadRecentSleepSessions(UUID userId, OffsetDateTime now);

    /**
     * Reads the target day's schedulable executables ranked by their persisted {@code priority_score}
     * (highest first — the floor reads the score, never recomputes the Prioritizer), each carrying its
     * remaining-effort inputs and {@code energy_drain}.
     *
     * <p>Completed work is dropped so a (re)plan never re-schedules what is already done: a terminal
     * {@code DONE} status is excluded, and so is any executable whose completion clock
     * ({@code last_completed_at}) falls inside the target day — the latter keeps a recurring executable
     * that was checked off today from reappearing on today's plan while leaving it schedulable on future
     * days (its completion clock is then before the day). This is the intraday-replan case: in the
     * morning nothing is completed yet, so the guard is a no-op there.
     *
     * @param userId   the owning user
     * <p>Work already held by a block this run may not re-time is not a candidate either: a
     * {@code USER} block the user timed by hand, one already under way, or a planner block whose start
     * has gone by. Containment is monovalent, so offering such an executable would silently pull it out
     * of the block that holds it — the guard has to live in the admission, because by the time a block
     * row is written the move has already happened.
     *
     * @param userId    the owning user
     * @param dayStart  the target day's start instant (inclusive), for the completed-today guard
     * @param dayEnd    the target day's end instant (exclusive), for the completed-today guard
     * @param notBefore the earliest block start this run may still re-time; blocks older than it hold
     *                  their members
     * @return the ranked schedulables; never null, may be empty
     */
    List<SchedulableExecutable> loadRankedExecutables(UUID userId, OffsetDateTime dayStart,
                                                      OffsetDateTime dayEnd, OffsetDateTime notBefore);

    /**
     * Reads the day's WIG portfolio: the {@code ACTIVE MCI} cycles ({@code CoreCycle type=MCI}), each
     * carrying its lead measure to reserve (or none, when the MCI has no {@code LEAD_MEASURE}), its
     * {@code estimated_minutes}-weighted aggregated progress, its remaining-window fraction (already
     * clamped to the borderline defaults), the completed flag, and the hysteresis/release-valve history
     * (whether its lead measure was held by a planner {@code TIME_BLOCK} yesterday, and its recent
     * block-less streak). The required-pace ordering and the reservation policy live in
     * {@code WigPortfolioSelector}.
     *
     * @param userId the owning user
     * @param now    the reference instant used to compute the remaining-window fraction and history
     * @return the active-MCI portfolio; never null, may be empty
     */
    List<MciWig> loadWigPortfolio(UUID userId, OffsetDateTime now);

    /**
     * Reads the hard walls to plan around, from every kind of row that holds time on the planning day:
     * <ul>
     *   <li>the existing {@code TIME_BLOCK} executables (ADR-039 — every lifecycle state except the
     *       {@code FOCUS}-origin accounting rows);</li>
     *   <li>the read-only AGENDA executable windows (ADR-009);</li>
     *   <li>the standing commitments — {@code ACTIVITY} and {@code LEARNING_SESSION} — that are not yet
     *       settled. They own a calendar window, so they occupy it. That the planner may still move one
     *       in hour (ADR-040 D9, and only through {@code loadMovableCommitments}) does not make it
     *       weightless while it stands: <b>movable is not invisible</b>, and reading it as such is what
     *       let the day be laid on top of them.</li>
     * </ul>
     *
     * @param userId    the owning user
     * @param dayStart  the planning window lower bound
     * @param dayEnd    the planning window upper bound
     * @return the occupied intervals; never null
     */
    List<OccupiedInterval> loadOccupiedIntervals(UUID userId, OffsetDateTime dayStart,
                                                 OffsetDateTime dayEnd);

    /**
     * Reads the day's movable commitments: the open {@code ACTIVITY} and {@code LEARNING_SESSION} rows
     * whose window falls on the target day (ADR-040 D9, middle tier). They are never window members —
     * they carry a calendar window of their own — but the planner may move them in hour inside their
     * day.
     *
     * @param userId   the owning user; never null
     * @param dayStart the day's start instant (inclusive); never null
     * @param dayEnd   the day's end instant (exclusive); never null
     * @return the day's movable commitments, earliest first; never null, may be empty
     */
    List<MovableCommitment> loadMovableCommitments(UUID userId, OffsetDateTime dayStart,
                                                   OffsetDateTime dayEnd);

    /**
     * Re-reads the day's <b>regenerable</b> blocks — the {@code TIME_BLOCK} executables of
     * {@code origin = PLANNER} still in {@code PLANNED}, with the membership each currently holds
     * through {@code container_block_id} and the template slot each realises.
     *
     * <p>This is the reconciliation universe <b>and</b> the starting point of the conservative replan
     * (ADR-040 D8): a replan does not start from nothing, it starts from the plan that is already
     * there. Everything outside this set is untouchable: {@code USER} blocks, blocks already
     * {@code IN_PROGRESS} or closed, and the read-only agenda.
     *
     * <p><b>{@code notBefore} is what makes "the past is never rewritten" an invariant rather than a
     * convention.</b> A block whose start has already gone by is not regenerable: it is not re-timed,
     * it is not withdrawn, and — because this same read feeds the wall subtraction — it keeps walling
     * the time it took. A morning already lived is not a draft.
     *
     * @param userId    the owning user; never null
     * @param targetDay the calendar day to read; never null
     * @param zone      the user's timezone used to bound the local day; never null
     * @param notBefore the earliest start this run may still touch; never null
     * @return the day's regenerable blocks, chronologically ordered; never null, may be empty
     */
    List<PlannerBlockIdentity.PersistedBlock> loadRegenerableBlocks(UUID userId, LocalDate targetDay,
                                                                    ZoneId zone,
                                                                    OffsetDateTime notBefore);

    /**
     * Inserts or re-times one planner block as a {@code TIME_BLOCK} executable (ADR-039: the block IS
     * the executable), under the id the identity reconciliation assigned it — so a continued block keeps
     * its {@code sync_mapping} and its calendar event is <b>updated</b> rather than duplicated (#15).
     *
     * <p><b>Nothing at all is written when nothing moved</b>: the statement's own guard makes an
     * unchanged block a zero-row write, so a replan that changes two blocks announces two, not thirty
     * (ADR-040 D17).
     *
     * <p>The row's name is persisted as given. Grouping and naming are the LLM's work and they survive
     * a replan untouched (ADR-040 D8), but that is enforced by the caller — which passes the block's
     * current name back in whenever it must not change — precisely so that a block published under a
     * bad name can still be corrected in place.
     *
     * @param block the block row to persist; never null
     * @return true when the row was actually inserted or moved — the caller's signal to announce it
     */
    boolean upsertBlock(PlannerBlockRow block);

    /**
     * Re-reads the persisted {@code PLANNED}/{@code PLANNER} blocks of the target day with their
     * members' display names, for the agenda write-back (HU-01b). Ordered by start instant so the
     * emitted reminders follow the day's chronology.
     *
     * @param userId    the owning user; never null
     * @param targetDay the calendar day to read; never null
     * @param zone      the user's timezone used to bound the local day; never null
     * @return the day's planner blocks with their executable names; never null, may be empty
     */
    List<PlannedBlockRecord> loadPlannedBlocksForDay(UUID userId, LocalDate targetDay, ZoneId zone);

    /**
     * Finds the user's cycle carrying an exact name. Used to attach system-authored executables to the
     * commitment they belong to (the nap that goes under «Sueño») without hardcoding an id: the cycle
     * is the user's, created by the user, and its id differs per environment.
     *
     * @param userId    the owning user; never null
     * @param cycleName the exact cycle name to look up; never null
     * @return the cycle's id, or empty when the user has no cycle by that name
     */
    Optional<UUID> findCycleIdByName(UUID userId, String cycleName);

    /**
     * Finds an {@code ACTIVITY} of the given cycle whose window overlaps {@code [start, end)} — the
     * natural key of an observed episode, which has no external id of its own.
     *
     * <p>This is what makes recording an observation idempotent: the same episode re-observed (the
     * dump is re-sent on every replan, and the watch revises its staging) matches the row already
     * there instead of creating a second one, with a second calendar event behind it.
     *
     * @param userId  the owning user; never null
     * @param cycleId the cycle the activity must belong to; never null
     * @param start   the observed window's start; never null
     * @param end     the observed window's end; never null
     * @return the earliest overlapping activity's id, or empty when none overlaps
     */
    Optional<UUID> findActivityOverlapping(UUID userId, UUID cycleId, OffsetDateTime start,
                                           OffsetDateTime end);

    /**
     * Inserts an already-completed {@code ACTIVITY}: an episode the system observed after the fact,
     * not a plan. Written with the planner's own origin and {@code system_generated = false} so the
     * standard propagators mirror it to Notion and to the calendar like any other executable.
     *
     * @param activityId the id to persist it under; never null
     * @param userId     the owning user; never null
     * @param cycleId    the cycle it belongs to; never null
     * @param name       the executable's display name; never null
     * @param start      when the episode began; never null
     * @param end        when it ended; never null and after {@code start}
     */
    void insertCompletedActivity(UUID activityId, UUID userId, UUID cycleId, String name,
                                 OffsetDateTime start, OffsetDateTime end);

    /**
     * Moves an activity's end instant to a revised one, leaving everything else untouched.
     *
     * @param activityId the activity to re-time; never null
     * @param end        the revised end instant; never null
     * @return true when the row actually moved — the caller's signal to announce it, so an unchanged
     *         re-observation stays silent instead of churning the satellites
     */
    boolean retimeActivityEnd(UUID activityId, OffsetDateTime end);

    /**
     * Reads the display names of the given executables for the LLM-facing read model (#61, H3): the
     * {@code core_executable.name} of each candidate block, so the cognitive proposer can present the
     * titles to the model as <b>untrusted, delimited</b> content (anti prompt-injection). Only queried
     * on the LLM path (when {@code app.cognitive.llm-propose.enabled} is on), so the floor path carries
     * no extra read.
     *
     * @param executableIds the executables whose titles to read; never null, may be empty
     * @return a map from executable id to display name; never null, missing ids simply absent
     */
    Map<UUID, String> loadExecutableTitles(Collection<UUID> executableIds);
}
