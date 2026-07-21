package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The H0 daily rollup (#17): adherence plus the three behavioral lead measures for one local day.
 * The judge of whether the future LLM layer adds value over the deterministic floor, and the signal
 * behind the 14-day MVP goal. Emitted as a structured JSON line to stdout (ADR-016 raw-first, no new
 * table in the MVP).
 *
 * @param date            the local day the rollup covers; never null
 * @param zone            the user's timezone the day is reasoned in; never null
 * @param blocksPlanned   planner-origin blocks placed for the day
 * @param blocksExecuted  how many of them were executed (settled past the temporal tolerance)
 * @param adherence       {@code blocksExecuted / blocksPlanned}, or 0 when nothing was planned
 * @param wigHit          true when the reserved WIG block (F1) was executed
 * @param ritualCompleted proxy for the ADR-018 morning commitment ritual — the dispatch fired for
 *                        the day; the true user-commitment signal is deferred (partial lead measure)
 * @param replanCount     how many {@code REPLAN_AGENDA} commands the user issued that day
 * @param abandoned       true when adherence fell below the threshold with zero replans (a day let
 *                        go, distinct from a day actively re-adjusted)
 */
public record DailyAdherenceReport(
    LocalDate date,
    ZoneId zone,
    int blocksPlanned,
    int blocksExecuted,
    double adherence,
    boolean wigHit,
    Boolean ritualCompleted,
    int replanCount,
    boolean abandoned
) {
}
