package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.ParsedSleepDay;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.model.SleepStageSample;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Distils a raw {@link DeviceSleepSamples} dump (HealthKit stages forwarded by the iOS Shortcut) into
 * the scorable sleep of the current sleep day: the night <em>plus every nap that followed it</em>.
 * Pure domain — beyond the dump it only needs the user's timezone (the provider's local date strings
 * omit it) and the reference instant to anchor the period on.
 *
 * <p>The pipeline:
 * <ol>
 *   <li><b>Parse</b> each sample's local {@code start}/{@code end} in the user's zone. Apple emits the
 *       hour in {@code d/M/yyyy 'at' h:mm a} form with a U+202F narrow no-break space before AM/PM,
 *       which is normalized to a plain space first. An unparseable sample or unknown stage is skipped
 *       (tolerant reader), never fatal.</li>
 *   <li><b>Cluster</b> the samples into sessions, splitting whenever a sample starts more than
 *       {@link #DEFAULT_SESSION_GAP} after the running end of the current session.</li>
 *   <li><b>Keep the sessions of the sleep day</b> — see {@link #sleepDayWindow}. Every one of them,
 *       not just the last: keeping only the most recent session meant an afternoon nap displaced the
 *       night whole, and the day was scored on the nap alone (production, 09:20–13:56, score 13).</li>
 *   <li><b>Resolve overlaps</b> inside each session, not just within a stage. Apple Watch revises
 *       stages, so intervals overlap both within and <em>across</em> stages (Core/Deep/REM tracks pile
 *       up). Total sleep time is the union of all asleep intervals on a single timeline — which bounds
 *       {@code TST ≤ TIB} — and each contested instant is <b>split proportionally</b> among the stages
 *       covering it, so the per-stage seconds sum exactly to TST (no double-counting) without inventing
 *       a winner. See {@link #resolveAsleepSeconds}.</li>
 *   <li><b>Sum</b> the sessions into one {@link AggregatedSleep} (Daniel, 2026-08-07): durations add
 *       up, the longest session keeps the row's real hours, and every session survives as a
 *       {@link SleepSession} so the day knows <em>when</em> he slept, not only how much.</li>
 *   <li><b>Refuse an implausible dump</b> — see {@link #verifyPlausible}. A volume of sleep that does
 *       not fit in the time that was measured is a corrupt reading, not a night.</li>
 * </ol>
 *
 * <p>Throws {@link IllegalArgumentException} when no usable sleep can be built (no parseable samples,
 * nothing inside the sleep day, a degenerate zero-length window, or an implausible volume); the caller
 * logs it and proceeds with the replan (the sleep is enrichment, not the primary action).
 */
public class SleepSampleSessionParser {

    /**
     * A gap larger than this between the running session end and the next sample's start starts a new
     * session. Three hours separates distinct sleeps while tolerating long mid-night awake stretches.
     */
    public static final Duration DEFAULT_SESSION_GAP = Duration.ofHours(3);

    /**
     * The hour at which a sleep day opens and the previous one closes: nobody starts the night before
     * this, so a window anchored on it reaches back over the night without reaching into the previous
     * day's waking hours. It is one boundary used twice — that is what makes the sleep days a
     * <em>partition</em> of the timeline.
     */
    private static final LocalTime SLEEP_DAY_OPENS_AT = LocalTime.of(18, 0);

    /**
     * The most sleep a single sleep day can plausibly hold. Beyond it the dump is a corrupt reading —
     * duplicated days, a mis-parsed year — not a long night.
     */
    private static final Duration MAX_TOTAL_SLEEP = Duration.ofHours(16);

    /** The widest measured window a sleep day can carry: one day's worth of readings, no more. */
    private static final Duration MAX_MEASURED_WINDOW = Duration.ofHours(24);

    /**
     * How far asleep + awake time may exceed the window that was measured before the dump is refused.
     * A little slack absorbs the seconds Apple's boundaries disagree by; more than that means the
     * reading claims more sleep than there was time for.
     */
    private static final double MEASURED_WINDOW_TOLERANCE = 1.05;

    private static final DateTimeFormatter LOCAL_FORMAT =
        DateTimeFormatter.ofPattern("d/M/yyyy 'at' h:mm a", Locale.ENGLISH);
    private static final char NARROW_NO_BREAK_SPACE = '\u202f';
    private static final char NO_BREAK_SPACE = '\u00a0';

    private final Duration sessionGap;

    /** Creates a parser with the sanctioned {@link #DEFAULT_SESSION_GAP}. */
    public SleepSampleSessionParser() {
        this(DEFAULT_SESSION_GAP);
    }

    /**
     * Creates a parser with an explicit session gap.
     *
     * @param sessionGap the minimum gap that separates two sleep sessions; never null
     */
    public SleepSampleSessionParser(Duration sessionGap) {
        if (sessionGap == null || sessionGap.isNegative() || sessionGap.isZero()) {
            throw new IllegalArgumentException("session gap must be a positive duration");
        }
        this.sessionGap = sessionGap;
    }

    /**
     * Distils the dump into the sleep of the current sleep day.
     *
     * @param dump      the raw sleep-stage dump; never null
     * @param zone      the user's timezone, applied to the provider's zone-less local strings; never null
     * @param reference the instant to anchor the sleep day on when the dump carries no usable capture
     *                  date (the command's {@code occurred_at}); never null
     * @return the aggregated sleep and its collection instant
     * @throws IllegalArgumentException when no scorable sleep can be built from the dump
     */
    public ParsedSleepDay parse(DeviceSleepSamples dump, ZoneId zone, OffsetDateTime reference) {
        if (dump == null || zone == null || reference == null) {
            throw new IllegalArgumentException("sleep dump, zone and reference instant are required");
        }
        List<StageInterval> parsed = new ArrayList<>();
        for (DeviceSleepSamples.Sample sample : dump.samples()) {
            StageCategory category = StageCategory.of(sample.stage());
            if (category == null) {
                continue; // unknown stage label: ignored (tolerant reader)
            }
            OffsetDateTime start = parseInstant(sample.start(), zone);
            OffsetDateTime end = parseInstant(sample.end(), zone);
            if (start == null || end == null || end.isBefore(start)) {
                continue; // unparseable or inverted interval: skipped
            }
            parsed.add(new StageInterval(category, start, end));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("no parseable sleep samples in the dump");
        }

        OffsetDateTime anchor = anchorOf(dump, zone, reference);
        List<SessionSummary> sessions = sessionsWithin(parsed, sleepDayWindow(anchor, zone));
        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("no sleep session in the sleep day anchored at " + anchor);
        }
        AggregatedSleep sleep = sum(sessions, zone);
        verifyPlausible(sleep);
        return new ParsedSleepDay(sleep, anchor);
    }

    /**
     * The local day whose sleep this dump is about, resolved by the same anchor rule {@link #parse}
     * uses and <b>without interpreting a single sample</b>. The sleep day spans two calendar dates and
     * is labelled by the later one — the day he woke into, which is the anchor's own local date.
     *
     * <p>This exists so the raw dump can be filed under the night it belongs to <em>before</em> it is
     * interpreted (raw-first): a dump the plausibility guards refuse must still be archived, and it is
     * precisely the one worth keeping for diagnosis.
     *
     * @param dump      the raw sleep-stage dump; never null
     * @param zone      the user's timezone; never null
     * @param reference the fallback anchor when the dump carries no usable capture date; never null
     * @return the sleep day's local date
     */
    public LocalDate sleepDayOf(DeviceSleepSamples dump, ZoneId zone, OffsetDateTime reference) {
        if (dump == null || zone == null || reference == null) {
            throw new IllegalArgumentException("sleep dump, zone and reference instant are required");
        }
        return anchorOf(dump, zone, reference).atZoneSameInstant(zone).toLocalDate();
    }

    /** The instant the sleep day is anchored on: the dump's own capture date, else the caller's. */
    private static OffsetDateTime anchorOf(DeviceSleepSamples dump, ZoneId zone,
                                           OffsetDateTime reference) {
        OffsetDateTime capturedAt = parseInstant(dump.capturedAt(), zone);
        return capturedAt != null ? capturedAt : reference;
    }

    /**
     * The sleep day the anchor falls in: it opens at {@link #SLEEP_DAY_OPENS_AT} on the previous local
     * day and closes at the earlier of the anchor and {@link #SLEEP_DAY_OPENS_AT} on the anchor's own
     * local day.
     *
     * <p><b>Why both ends, and why the same hour.</b> With only a lower bound plus a rolling 24 h clamp
     * the two cuts did not meet: an evening nap fell outside the clamp of tonight's run and outside the
     * lower bound of tomorrow's (lost), or landed inside both (counted twice on two rows). Using the
     * same 18:00 to close a sleep day and open the next one makes the days a partition of the timeline —
     * together with the membership rule in {@link SleepDayWindow#holds}, every instant of sleep belongs
     * to exactly one row, whatever hour the replan happens to run at.
     *
     * <p>Closing at the anchor when the run is earlier than 18:00 is not a third rule: sleep that has
     * not happened yet cannot be in the dump, and it keeps the measured window honest.
     */
    private static SleepDayWindow sleepDayWindow(OffsetDateTime anchor, ZoneId zone) {
        LocalDate localDay = anchor.atZoneSameInstant(zone).toLocalDate();
        OffsetDateTime opensAt = localDay.minusDays(1)
            .atTime(SLEEP_DAY_OPENS_AT).atZone(zone).toOffsetDateTime();
        OffsetDateTime eveningCut = localDay
            .atTime(SLEEP_DAY_OPENS_AT).atZone(zone).toOffsetDateTime();
        return new SleepDayWindow(opensAt, anchor.isBefore(eveningCut) ? anchor : eveningCut);
    }

    /**
     * Splits the samples into sessions by the gap threshold and aggregates those that belong to the
     * sleep day, chronologically. Membership is decided by the session's <em>start</em> and the session
     * is then kept <b>whole</b>, never truncated: a night that began at 23:00 is summed entire on the
     * day its 18:00 window opened, and no other day may claim any part of it.
     */
    private List<SessionSummary> sessionsWithin(List<StageInterval> intervals, SleepDayWindow window) {
        intervals.sort(Comparator.comparing(StageInterval::start));
        List<SessionSummary> kept = new ArrayList<>();
        List<StageInterval> current = new ArrayList<>();
        OffsetDateTime runningEnd = null;
        for (StageInterval interval : intervals) {
            if (runningEnd != null && Duration.between(runningEnd, interval.start()).compareTo(sessionGap) > 0) {
                keepIfWithin(kept, current, window);
                current = new ArrayList<>();
                runningEnd = null;
            }
            current.add(interval);
            if (runningEnd == null || interval.end().isAfter(runningEnd)) {
                runningEnd = interval.end();
            }
        }
        keepIfWithin(kept, current, window);
        return kept;
    }

    /** Aggregates a closed session into the kept list when the sleep day holds its start. */
    private static void keepIfWithin(List<SessionSummary> kept, List<StageInterval> session,
                                     SleepDayWindow window) {
        // The intervals were sorted by start before clustering, so the first one holds the session's.
        if (session.isEmpty() || !window.holds(session.getFirst().start())) {
            return;
        }
        SessionSummary summary = aggregate(session);
        if (summary != null) {
            kept.add(summary);
        }
    }

    /**
     * Sums the sleep day's sessions into the aggregate the row is built from: stage durations add up,
     * the scorable window spans the summed time in bed (never the awake gaps between sessions — see
     * {@link AggregatedSleep}), the night cluster gives the row its real hours, and the contested
     * seconds add up into the aggregate's overlap measurement. The zone is needed because where the
     * night ends is decided against the user's own morning, not against an offset.
     */
    private static AggregatedSleep sum(List<SessionSummary> summaries, ZoneId zone) {
        long inBed = 0;
        long core = 0;
        long deep = 0;
        long rem = 0;
        long unspecified = 0;
        long awake = 0;
        long timeInBed = 0;
        long overlap = 0;
        List<SleepSession> sessions = new ArrayList<>(summaries.size());
        for (SessionSummary summary : summaries) {
            SleepStageSample sample = summary.sample();
            inBed += sample.inBedSeconds();
            core += sample.coreSeconds();
            deep += sample.deepSeconds();
            rem += sample.remSeconds();
            unspecified += sample.unspecifiedSeconds();
            awake += sample.awakeSeconds();
            timeInBed += Duration.between(sample.start(), sample.end()).toSeconds();
            overlap += summary.overlapSeconds();
            sessions.add(new SleepSession(sample.start(), sample.end(), sample.totalSleepSeconds()));
        }
        SleepSession night = AggregatedSleep.nightOf(sessions, zone);
        SleepStageSample totals = new SleepStageSample(
            night.start(), night.start().plusSeconds(timeInBed),
            inBed, core, deep, rem, unspecified, awake);
        return new AggregatedSleep(totals, night, sessions, overlap);
    }

    /**
     * Refuses a dump whose volume does not fit the time it claims to have measured. These are input
     * guards, not scoring: a reading that fails one of them is corrupt, and scoring it would put a
     * plausible-looking number on a day that was never measured.
     *
     * @throws IllegalArgumentException when the day holds more sleep than its window can carry, more
     *                                  than {@link #MAX_TOTAL_SLEEP}, or was measured over a window
     *                                  wider than {@link #MAX_MEASURED_WINDOW}
     */
    private static void verifyPlausible(AggregatedSleep sleep) {
        SleepStageSample totals = sleep.totals();
        long windowSeconds = Duration.between(totals.start(), totals.end()).toSeconds();
        long asleepAndAwake = totals.totalSleepSeconds() + totals.awakeSeconds();
        if (asleepAndAwake > MEASURED_WINDOW_TOLERANCE * windowSeconds) {
            throw new IllegalArgumentException("implausible sleep dump: " + asleepAndAwake
                + "s asleep+awake exceed the " + windowSeconds + "s window that was measured");
        }
        if (totals.totalSleepSeconds() > MAX_TOTAL_SLEEP.toSeconds()) {
            throw new IllegalArgumentException("implausible sleep dump: " + totals.totalSleepSeconds()
                + "s of sleep exceed the " + MAX_TOTAL_SLEEP.toHours() + "h a sleep day can hold");
        }
        if (windowSeconds > MAX_MEASURED_WINDOW.toSeconds()) {
            throw new IllegalArgumentException("implausible sleep dump: a " + windowSeconds
                + "s measured window exceeds the " + MAX_MEASURED_WINDOW.toHours() + "h of a sleep day");
        }
    }

    /**
     * Aggregates one session: asleep stages are overlap-resolved (proportional split) so they sum to
     * the asleep-union TST; Awake and In Bed are plain unions; the window spans all its samples.
     *
     * @return the session's sample and its contested seconds, or null when its window is degenerate
     *         (zero-length) — such a session carries no time and is dropped rather than failing the
     *         whole dump
     */
    private static SessionSummary aggregate(List<StageInterval> session) {
        List<StageInterval> asleep = new ArrayList<>();
        List<StageInterval> awake = new ArrayList<>();
        List<StageInterval> inBed = new ArrayList<>();
        OffsetDateTime windowStart = null;
        OffsetDateTime windowEnd = null;
        for (StageInterval interval : session) {
            switch (interval.category()) {
                case AWAKE -> awake.add(interval);
                case IN_BED -> inBed.add(interval);
                default -> asleep.add(interval); // CORE, DEEP, REM, UNSPECIFIED
            }
            if (windowStart == null || interval.start().isBefore(windowStart)) {
                windowStart = interval.start();
            }
            if (windowEnd == null || interval.end().isAfter(windowEnd)) {
                windowEnd = interval.end();
            }
        }
        if (!windowEnd.isAfter(windowStart)) {
            return null;
        }
        AsleepBreakdown breakdown = resolveAsleepSeconds(asleep);
        Map<StageCategory, Long> asleepSeconds = breakdown.seconds();
        return new SessionSummary(new SleepStageSample(
            windowStart, windowEnd,
            unionSeconds(inBed),
            asleepSeconds.get(StageCategory.CORE),
            asleepSeconds.get(StageCategory.DEEP),
            asleepSeconds.get(StageCategory.REM),
            asleepSeconds.get(StageCategory.UNSPECIFIED),
            unionSeconds(awake)), breakdown.overlapSeconds());
    }

    /**
     * Splits every covered instant of the asleep intervals among the stages that cover it (sweep-line),
     * and measures how much of the sleep was contested. The four returned values sum to the union of all
     * asleep intervals (TST), so overlapping Core/Deep/REM tracks are never double-counted.
     *
     * <p><b>Why proportional and not "deepest wins"</b> (expert committee, 2026-08-08). Apple Watch
     * revises its staging, and a revision overlaps the reading it revises; a precedence rule then hands
     * every contested second to the deeper of the two tracks. Over the six production rows that pushed
     * the Core residue down to 34–35 % against an adult N1+N2 norm of 45–55 %, and one night alone to a
     * deep fraction of 0.513 — physiologically impossible. The overlap carries no information about
     * which stage is right, so the only defensible reading is that it carries no information at all:
     * each of the {@code N} active stages takes {@code span / N}.
     *
     * <p>The split is done in whole seconds and the remainder of the integer division goes to the
     * deepest active stage, so the four values still sum to TST <em>exactly</em> rather than to within a
     * rounding error. (On real dumps the boundaries fall on whole minutes, so the remainder is zero;
     * the rule exists so the invariant does not depend on that.)
     */
    private static AsleepBreakdown resolveAsleepSeconds(List<StageInterval> asleep) {
        Map<StageCategory, Long> seconds = new EnumMap<>(StageCategory.class);
        for (StageCategory category : StageCategory.ASLEEP) {
            seconds.put(category, 0L);
        }
        if (asleep.isEmpty()) {
            return new AsleepBreakdown(seconds, 0L);
        }
        NavigableMap<OffsetDateTime, List<StageCategory>> starts = new TreeMap<>();
        NavigableMap<OffsetDateTime, List<StageCategory>> ends = new TreeMap<>();
        TreeSet<OffsetDateTime> boundaries = new TreeSet<>();
        for (StageInterval interval : asleep) {
            starts.computeIfAbsent(interval.start(), key -> new ArrayList<>()).add(interval.category());
            ends.computeIfAbsent(interval.end(), key -> new ArrayList<>()).add(interval.category());
            boundaries.add(interval.start());
            boundaries.add(interval.end());
        }
        Map<StageCategory, Integer> active = new EnumMap<>(StageCategory.class);
        List<OffsetDateTime> ordered = new ArrayList<>(boundaries);
        long overlapSeconds = 0L;
        for (int i = 0; i < ordered.size() - 1; i++) {
            OffsetDateTime from = ordered.get(i);
            // Apply this boundary's events before measuring [from, next): an interval ending here is no
            // longer active on the sub-interval, one starting here is.
            for (StageCategory category : ends.getOrDefault(from, List.of())) {
                active.merge(category, -1, Integer::sum);
            }
            for (StageCategory category : starts.getOrDefault(from, List.of())) {
                active.merge(category, 1, Integer::sum);
            }
            List<StageCategory> covering = activeDeepestFirst(active);
            if (covering.isEmpty()) {
                continue;
            }
            long span = Duration.between(from, ordered.get(i + 1)).toSeconds();
            long share = span / covering.size();
            for (StageCategory category : covering) {
                seconds.merge(category, share, Long::sum);
            }
            seconds.merge(covering.getFirst(), span - share * covering.size(), Long::sum);
            if (covering.size() > 1) {
                overlapSeconds += span;
            }
        }
        return new AsleepBreakdown(seconds, overlapSeconds);
    }

    /** The asleep stages covering the sweep position, deepest first; empty when none is active. */
    private static List<StageCategory> activeDeepestFirst(Map<StageCategory, Integer> active) {
        List<StageCategory> covering = new ArrayList<>(StageCategory.ASLEEP.size());
        for (StageCategory category : StageCategory.ASLEEP) {
            Integer depth = active.get(category);
            if (depth != null && depth > 0) {
                covering.add(category);
            }
        }
        return covering;
    }

    /** Total seconds covered by the union of the intervals (overlaps counted once); 0 when none. */
    private static long unionSeconds(List<StageInterval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return 0L;
        }
        intervals.sort(Comparator.comparing(StageInterval::start));
        long total = 0L;
        OffsetDateTime coverStart = null;
        OffsetDateTime coverEnd = null;
        for (StageInterval interval : intervals) {
            if (coverStart == null) {
                coverStart = interval.start();
                coverEnd = interval.end();
            } else if (!interval.start().isAfter(coverEnd)) {
                if (interval.end().isAfter(coverEnd)) {
                    coverEnd = interval.end();
                }
            } else {
                total += Duration.between(coverStart, coverEnd).toSeconds();
                coverStart = interval.start();
                coverEnd = interval.end();
            }
        }
        return total + Duration.between(coverStart, coverEnd).toSeconds();
    }

    /**
     * Parses a provider local date-time string into an instant in {@code zone}, normalizing the
     * U+202F/U+00A0 spaces Apple inserts before AM/PM. Returns null on absence or a parse failure.
     */
    private static OffsetDateTime parseInstant(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replace(NARROW_NO_BREAK_SPACE, ' ').replace(NO_BREAK_SPACE, ' ');
        try {
            return LocalDateTime.parse(normalized, LOCAL_FORMAT).atZone(zone).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** A parsed stage interval: the mapped category plus its resolved start/end instants. */
    private record StageInterval(StageCategory category, OffsetDateTime start, OffsetDateTime end) {
    }

    /**
     * The half-open sleep day {@code [opensAt, closesAt)} a dump is read against.
     *
     * @param opensAt  when the sleep day opens; never null
     * @param closesAt when it closes, exclusive; never null and after {@code opensAt}
     */
    private record SleepDayWindow(OffsetDateTime opensAt, OffsetDateTime closesAt) {

        /**
         * Whether this sleep day owns a session that began at {@code sessionStart}. Half-open on
         * purpose: the same instant must not belong to two consecutive days.
         */
        boolean holds(OffsetDateTime sessionStart) {
            return !sessionStart.isBefore(opensAt) && sessionStart.isBefore(closesAt);
        }
    }

    /** One clustered session already aggregated, with the sleep seconds more than one stage claimed. */
    private record SessionSummary(SleepStageSample sample, long overlapSeconds) {
    }

    /**
     * The per-stage seconds of one session and how many of them were contested.
     *
     * @param seconds        the four asleep stages' seconds, summing exactly to the asleep union (TST)
     * @param overlapSeconds the seconds of sleep covered by more than one stage at once — the measure
     *                       of how much the old precedence rule was displacing
     */
    private record AsleepBreakdown(Map<StageCategory, Long> seconds, long overlapSeconds) {
    }

    /**
     * The HealthKit stage buckets the scorer understands; unknown labels map to none (skipped).
     * {@code AWAKE} and {@code IN_BED} are not asleep stages and take no part in the overlap split.
     */
    private enum StageCategory {
        DEEP, REM, CORE, UNSPECIFIED, AWAKE, IN_BED;

        /**
         * The asleep stages that make up total sleep time, <b>deepest first</b> — the order is
         * load-bearing: it is what makes the remainder of the proportional split land deterministically.
         */
        static final List<StageCategory> ASLEEP = List.of(DEEP, REM, CORE, UNSPECIFIED);

        static StageCategory of(String stage) {
            if (stage == null) {
                return null;
            }
            return switch (stage.strip().toLowerCase(Locale.ROOT)) {
                case "core" -> CORE;
                case "deep" -> DEEP;
                case "rem" -> REM;
                case "awake" -> AWAKE;
                case "in bed", "inbed" -> IN_BED;
                case "asleep", "unspecified", "asleep unspecified" -> UNSPECIFIED;
                default -> null;
            };
        }
    }
}
