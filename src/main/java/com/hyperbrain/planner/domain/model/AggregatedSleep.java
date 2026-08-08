package com.hyperbrain.planner.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * The sleep a single {@code tel_sleep_record} row stands for: every session of the relevant period
 * summed into one scorable input (Daniel, 2026-08-07 — «súmalas»), plus the sessions themselves as
 * they happened on the clock.
 *
 * <p><b>Why the aggregate is not just a longer session.</b> A 6 h night followed by a 3 h nap is not a
 * 9 h night, and the three parts of this record keep those two truths apart instead of averaging them
 * into one misleading interval:
 * <ul>
 *   <li>{@code totals} — the scorable input: every stage duration summed across the sessions. Its
 *       <b>window is a duration carrier, not a claim about the clock</b>: it opens at the main
 *       session's start and lasts the <em>sum of the session spans</em>, so the calculator (which reads
 *       time in bed as {@code end - start}) sees the summed time in bed and never the huge awake gap
 *       between a night and an afternoon nap. Read it for durations; never for hours.</li>
 *   <li>{@code mainSession} — the longest session, the one that gives the row its real
 *       {@code start_time}/{@code end_time}. Those two columns are the chronotype the sleep frontier
 *       takes its wake/bedtime medians from, so a nap must never become the row's hours: it would move
 *       the learned wake time to the middle of the afternoon.</li>
 *   <li>{@code sessions} — every session in chronological order, night and naps alike. This is what
 *       tells the day <em>when</em> the user slept, not only how much.</li>
 *   <li>{@code overlapSeconds} — how much of the sleep more than one stage claimed at once. It changes
 *       no total (the stages are split over it, never summed twice); it is kept because without it
 *       there is no way to tell a night the watch read cleanly from one it revised three times, and no
 *       way to recalibrate the score afterwards against the rows already written.</li>
 * </ul>
 *
 * @param totals         the summed stage durations, in a window whose span is the summed time in bed
 * @param mainSession    the longest session — the row's real hours; never null
 * @param sessions       every summed session, chronological; never null nor empty
 * @param overlapSeconds seconds of sleep covered by two or more stages at once; never negative
 */
public record AggregatedSleep(SleepStageSample totals, SleepSession mainSession,
                              List<SleepSession> sessions, long overlapSeconds) {

    public AggregatedSleep {
        if (totals == null || mainSession == null) {
            throw new IllegalArgumentException("aggregated sleep requires totals and a main session");
        }
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("aggregated sleep requires at least one session");
        }
        if (overlapSeconds < 0) {
            throw new IllegalArgumentException("overlap seconds must be non-negative: " + overlapSeconds);
        }
        sessions = List.copyOf(sessions);
    }

    /**
     * Builds an aggregate whose stages were never contested — the shape of a payload that arrives
     * already aggregated, where no overlap was observed because none could be.
     *
     * @param totals      the summed stage durations; never null
     * @param mainSession the session giving the row its hours; never null
     * @param sessions    every summed session, chronological; never null nor empty
     */
    public AggregatedSleep(SleepStageSample totals, SleepSession mainSession,
                           List<SleepSession> sessions) {
        this(totals, mainSession, sessions, 0L);
    }

    /**
     * Builds the aggregate of a single, already-summed session — the shape the canonical telemetry
     * pipeline produces, where the payload is one session and the sample's own window is the truth.
     *
     * @param sample the session's stage durations and real window; never null
     * @return the aggregate whose totals, hours and only session are all that one sample
     */
    public static AggregatedSleep ofSingleSession(SleepStageSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("sleep sample must not be null");
        }
        SleepSession session =
            new SleepSession(sample.start(), sample.end(), sample.totalSleepSeconds());
        return new AggregatedSleep(sample, session, List.of(session));
    }

    /**
     * Picks the session that gives the row its hours: the longest by time asleep, earliest first on a
     * tie so a night is preferred over a nap of the same length that came later.
     *
     * @param sessions the period's sessions, chronological; never null nor empty
     * @return the main session
     */
    public static SleepSession mainOf(List<SleepSession> sessions) {
        return sessions.stream()
            .max(Comparator.comparingLong(SleepSession::asleepSeconds))
            .orElseThrow(() -> new IllegalArgumentException("no session to pick a main one from"));
    }
}
