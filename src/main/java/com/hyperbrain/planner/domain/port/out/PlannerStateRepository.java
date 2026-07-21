package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Out-port through which the deterministic agenda floor (#6a) reads the day's domain state directly
 * from the aggregates ({@code core_executable}, {@code core_time_block}, {@code core_cycle},
 * {@code tel_sleep_record}, {@code sys_user.settings}) and persists the resulting {@code PLANNED}
 * blocks. The floor does <b>not</b> consume the UserStateReadModel (#61) — that is the LLM tier's
 * contract (#6b), not the floor's (Daniel, 2026-07-09).
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
     * @param dayStart the target day's start instant (inclusive), for the completed-today guard
     * @param dayEnd   the target day's end instant (exclusive), for the completed-today guard
     * @return the ranked schedulables; never null, may be empty
     */
    List<SchedulableExecutable> loadRankedExecutables(UUID userId, OffsetDateTime dayStart,
                                                      OffsetDateTime dayEnd);

    /**
     * Reads the day's WIG portfolio: the {@code ACTIVE MCI} cycles ({@code CoreCycle type=MCI}), each
     * carrying its lead measure to reserve (or none, when the MCI has no {@code LEAD_MEASURE}), its
     * {@code estimated_minutes}-weighted aggregated progress, its remaining-window fraction (already
     * clamped to the borderline defaults), the completed flag, and the hysteresis/release-valve history
     * (whether its lead measure held a planner block yesterday, and its recent block-less streak). The
     * required-pace ordering and the reservation policy live in {@code WigPortfolioSelector}.
     *
     * @param userId the owning user
     * @param now    the reference instant used to compute the remaining-window fraction and history
     * @return the active-MCI portfolio; never null, may be empty
     */
    List<MciWig> loadWigPortfolio(UUID userId, OffsetDateTime now);

    /**
     * Reads the hard walls to plan around: existing open/settled {@code core_time_block} windows and
     * read-only AGENDA executable windows (ADR-009) that intersect the planning day.
     *
     * @param userId    the owning user
     * @param dayStart  the planning window lower bound
     * @param dayEnd    the planning window upper bound
     * @return the occupied intervals; never null
     */
    List<OccupiedInterval> loadOccupiedIntervals(UUID userId, OffsetDateTime dayStart,
                                                 OffsetDateTime dayEnd);

    /**
     * Reconciles the day's regenerable {@code PLANNED}/{@code PLANNER} blocks against a freshly
     * generated plan, <b>preserving block identity</b> so a regeneration converges without churning
     * the Apple calendar (#15). Each desired block is keyed by its stable id
     * ({@link com.hyperbrain.planner.domain.model.PlannerBlockIdentity}):
     * <ul>
     *   <li>a block that survives (same id) is <b>updated</b> in place (new start/end/reason), keeping
     *       its {@code core_time_block.id} and therefore its {@code sync_mapping} → the write-back
     *       emits an {@code UPDATE} of the existing EKEvent instead of a duplicate {@code CREATE};</li>
     *   <li>a genuinely new block is <b>inserted</b>;</li>
     *   <li>a block that dropped out of the new plan is <b>deleted</b>, and its id is returned so the
     *       caller can stage the deletion of its Apple counterpart (the mapping is not touched here —
     *       it is closed by the write-command result loop once Apple confirms the delete).</li>
     * </ul>
     * Scoped to {@code PLANNER}-origin {@code PLANNED} rows: {@code FOCUS}/{@code USER} blocks and any
     * {@code ACTIVE}/{@code SETTLED} work (which carries telemetry) are never touched, even when a
     * desired block's stable id would collide with such a row. Must run in the same transaction as the
     * write-back staging so the plan and its delivery are atomic.
     *
     * @param userId    the owning user; never null
     * @param targetDay the calendar day being reconciled; never null
     * @param zone      the user's timezone used to bound the local day; never null
     * @param desired   the accepted blocks the new plan wants for the day; never null, may be empty
     * @return the ids of previously-persisted {@code PLANNED} blocks that dropped out of the new plan;
     *         never null, may be empty
     */
    List<UUID> reconcilePlannedBlocks(UUID userId, LocalDate targetDay, ZoneId zone,
                                      List<AgendaBlock> desired);

    /**
     * Re-reads the persisted {@code PLANNED}/{@code PLANNER} blocks of the target day, each joined to
     * its executable's display name, for the agenda write-back (HU-01b). Ordered by {@code date_start}
     * so the emitted reminders follow the day's chronology.
     *
     * @param userId    the owning user; never null
     * @param targetDay the calendar day to read; never null
     * @param zone      the user's timezone used to bound the local day; never null
     * @return the day's planner blocks with their executable names; never null, may be empty
     */
    List<PlannedBlockRecord> loadPlannedBlocksForDay(UUID userId, LocalDate targetDay, ZoneId zone);
}
