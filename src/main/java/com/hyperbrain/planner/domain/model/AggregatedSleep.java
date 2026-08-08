package com.hyperbrain.planner.domain.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The sleep a single {@code tel_sleep_record} row stands for: every session of the relevant period
 * summed into one scorable input (Daniel, 2026-08-07 — «súmalas»), plus the sessions themselves as
 * they happened on the clock.
 *
 * <p><b>Why the aggregate is not just a longer session.</b> A 6 h night followed by a 3 h nap is not a
 * 9 h night, and the parts of this record keep those truths apart instead of averaging them into one
 * misleading interval:
 * <ul>
 *   <li>{@code totals} — the scorable input: every stage duration summed across the sessions. Its
 *       <b>window is a duration carrier, not a claim about the clock</b>: it opens at the night's start
 *       and lasts the <em>sum of the session spans</em>, so the calculator (which reads time in bed as
 *       {@code end - start}) sees the summed time in bed and never the huge awake gap between a night
 *       and an afternoon nap — nor the one between two fragments of the same night. Read it for
 *       durations; never for hours.</li>
 *   <li>{@code night} — the night <b>on the clock</b>: from the start of its first fragment to the end
 *       of its last. This is what gives the row its real {@code start_time}/{@code end_time}, and those
 *       two columns are the chronotype the sleep frontier takes its wake/bedtime medians from. Its
 *       span is therefore a genuine claim about the clock and is <em>wider</em> than its
 *       {@code asleepSeconds} whenever the night was broken.</li>
 *   <li>{@code sessions} — every session in chronological order, night fragments and naps alike. This
 *       is what tells the day <em>when</em> the user slept, not only how much.</li>
 *   <li>{@code overlapSeconds} — how much of the sleep more than one stage claimed at once. It changes
 *       no total (the stages are split over it, never summed twice); it is kept because without it
 *       there is no way to tell a night the watch read cleanly from one it revised three times, and no
 *       way to recalibrate the score afterwards against the rows already written.</li>
 * </ul>
 *
 * <p><b>The night is a cluster, not the longest session</b> (Daniel, 2026-08-08 — «anoche dormí en dos
 * fases porque me desperté en la madrugada»). Reading the row's hours off the longest session alone
 * broke a fragmented night twice over: a 23:00–03:00 stretch followed by 06:30–08:00 declared that he
 * got up at 03:00 — and the system learned that as his wake time — while filing the 06:30 stretch as a
 * nap, with a calendar event at half past six in the morning. See {@link #nightOf}.
 *
 * @param totals         the summed stage durations, in a window whose span is the summed time in bed
 * @param night          the night's real span on the clock — the row's hours; never null
 * @param sessions       every summed session, chronological; never null nor empty
 * @param overlapSeconds seconds of sleep covered by two or more stages at once; never negative
 */
public record AggregatedSleep(SleepStageSample totals, SleepSession night,
                              List<SleepSession> sessions, long overlapSeconds) {

    /**
     * The longest a break may last and still leave the sleep around it one night. Below it he did not
     * get up to start the day — he woke in the small hours and went back to sleep, and the two stretches
     * are one night with a hole in it.
     *
     * <p>Deliberately far wider than the gap that separates <em>sessions</em> in the raw dump (3 h):
     * the two thresholds do different jobs. The session gap decides where one stretch of sleep ends and
     * the next begins, which is a question about the recording; this one decides whether the next
     * stretch is still the same night, which is a question about the person.
     */
    private static final Duration SAME_NIGHT_GAP = Duration.ofHours(5);

    public AggregatedSleep {
        if (totals == null || night == null) {
            throw new IllegalArgumentException("aggregated sleep requires totals and a night");
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
     * @param totals   the summed stage durations; never null
     * @param night    the night's real span — the row's hours; never null
     * @param sessions every summed session, chronological; never null nor empty
     */
    public AggregatedSleep(SleepStageSample totals, SleepSession night, List<SleepSession> sessions) {
        this(totals, night, sessions, 0L);
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
     * The day's naps: every session that falls outside the night, chronologically. A nap is defined by
     * <em>exclusion</em> and not by length, which is what keeps a 90-minute stretch at half past six
     * from being filed as one: it is inside the night, so it is night.
     *
     * <p>Membership is exact rather than approximate. The night is a contiguous run of sessions, so a
     * session sits inside the run precisely when its window sits inside the night's span.
     *
     * @return the sessions outside the night; never null, empty when the day was only a night
     */
    public List<SleepSession> naps() {
        List<SleepSession> naps = new ArrayList<>();
        for (SleepSession session : sessions) {
            if (session.start().isBefore(night.start()) || session.end().isAfter(night.end())) {
                naps.add(session);
            }
        }
        return naps;
    }

    /**
     * Resolves the night out of the day's sessions: the longest one, grown outwards — backwards and
     * forwards alike — for as long as the break to the neighbouring session is shorter than
     * {@link #SAME_NIGHT_GAP}. The result spans from the first fragment's start to the last one's end
     * and carries the sleep of all of them.
     *
     * <p>The longest session is only the <em>seed</em>. What the night is, is the run of sleep it
     * belongs to: on a broken night neither fragment is the night on its own, and picking the longer of
     * the two puts the row's wake time in the middle of the small hours.
     *
     * <p><b>Known limit, accepted (Daniel, 2026-08-08).</b> The threshold is symmetric and it is coarse,
     * so an evening doze taken less than five hours before falling asleep is absorbed into the night —
     * and so is a morning nap taken less than five hours after waking. Both stretch the row's hours
     * outwards, which drags the learned bedtime earlier or the learned wake later. It is a far more
     * benign failure than labelling a stretch of the night a nap, and it is not being solved now.
     *
     * @param sessions the period's sessions, <b>chronological</b>; never null nor empty
     * @return the night: its real span on the clock and the sleep inside it
     */
    public static SleepSession nightOf(List<SleepSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("no session to resolve a night from");
        }
        int seed = sessions.indexOf(longestOf(sessions));
        int first = seed;
        while (first > 0 && sameNight(sessions.get(first - 1), sessions.get(first))) {
            first--;
        }
        int last = seed;
        while (last < sessions.size() - 1 && sameNight(sessions.get(last), sessions.get(last + 1))) {
            last++;
        }
        long asleep = 0;
        for (SleepSession fragment : sessions.subList(first, last + 1)) {
            asleep += fragment.asleepSeconds();
        }
        return new SleepSession(sessions.get(first).start(), sessions.get(last).end(), asleep);
    }

    /** Whether the break between two consecutive sessions is short enough to leave them one night. */
    private static boolean sameNight(SleepSession earlier, SleepSession later) {
        return Duration.between(earlier.end(), later.start()).compareTo(SAME_NIGHT_GAP) < 0;
    }

    /** The longest session by time asleep; the earliest wins a tie, so a night beats an equal nap. */
    private static SleepSession longestOf(List<SleepSession> sessions) {
        return sessions.stream()
            .max(Comparator.comparingLong(SleepSession::asleepSeconds))
            .orElseThrow();
    }
}
