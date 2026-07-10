package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The resolved, concrete-day state the {@code AgendaGenerator} materializes into an {@link Agenda}.
 * The read port and the sleep/energy resolvers assemble it so the generator is a pure function of
 * this input — no clock, no persistence:
 *
 * <ul>
 *   <li>{@code windowStart} / {@code windowEnd} — the concrete instants of the planning window for
 *       the target day, already the intersection of the sleep frontier {@code [wake, bedtime]} with
 *       the lower bound of the run (full-day: {@code wake}; replan-from-now: {@code max(wake, now)});</li>
 *   <li>{@code rankedExecutables} — the day's schedulables ordered by {@code priority_score}
 *       highest-first (the floor reads the score, never recomputes it);</li>
 *   <li>{@code wigPortfolio} — the active MCIs (1–3), for the F1 per-MCI reservation;</li>
 *   <li>{@code occupied} — the hard walls already spoken for (existing blocks, AGENDA windows);</li>
 *   <li>{@code energyProfile} — the resolved F3 margin / F6 quota and its readable criterion;</li>
 *   <li>{@code dataComplete} — false when a required signal was missing, steering the generator into
 *       the F5 degraded floor.</li>
 * </ul>
 *
 * @param windowStart       concrete planning-window start; never null
 * @param windowEnd         concrete planning-window end; never null, after {@code windowStart}
 * @param rankedExecutables schedulables ordered highest priority first; never null
 * @param wigPortfolio      the active MCIs for the F1 per-MCI reservation; never null, may be empty
 * @param occupied          the hard walls to plan around; never null
 * @param energyProfile     the resolved load parameters and criterion; never null
 * @param dataComplete      whether the inputs were complete (false → F5 degraded)
 */
public record PlannerDayState(
    OffsetDateTime windowStart,
    OffsetDateTime windowEnd,
    List<SchedulableExecutable> rankedExecutables,
    List<MciWig> wigPortfolio,
    List<OccupiedInterval> occupied,
    EnergyProfile energyProfile,
    boolean dataComplete
) {

    public PlannerDayState {
        if (windowStart == null || windowEnd == null) {
            throw new IllegalArgumentException("windowStart and windowEnd must not be null");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException(
                "windowEnd must be after windowStart: " + windowStart + " .. " + windowEnd);
        }
        if (energyProfile == null) {
            throw new IllegalArgumentException("energyProfile must not be null");
        }
        rankedExecutables = rankedExecutables == null ? List.of() : List.copyOf(rankedExecutables);
        wigPortfolio = wigPortfolio == null ? List.of() : List.copyOf(wigPortfolio);
        occupied = occupied == null ? List.of() : List.copyOf(occupied);
    }
}
