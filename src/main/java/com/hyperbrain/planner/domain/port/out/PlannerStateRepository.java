package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;

import java.time.OffsetDateTime;
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
     * Reads the day's schedulable executables ranked by their persisted {@code priority_score}
     * (highest first — the floor reads the score, never recomputes the Prioritizer), each carrying its
     * remaining-effort inputs and {@code energy_drain}.
     *
     * @param userId the owning user
     * @return the ranked schedulables; never null, may be empty
     */
    List<SchedulableExecutable> loadRankedExecutables(UUID userId);

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
     * Persists the validated {@code PLANNED} blocks with {@code origin = PLANNER}. Writes only new
     * block rows; it never mutates executables.
     *
     * @param blocks the accepted blocks to persist; never null, may be empty
     */
    void persistPlannedBlocks(List<AgendaBlock> blocks);
}
