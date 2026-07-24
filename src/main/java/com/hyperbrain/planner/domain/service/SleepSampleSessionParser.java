package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.ParsedSleepNight;
import com.hyperbrain.planner.domain.model.SleepStageSample;

import java.time.Duration;
import java.time.LocalDateTime;
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

/**
 * Distils a raw {@link DeviceSleepSamples} dump (HealthKit stages forwarded by the iOS Shortcut) into
 * the single scorable {@link ParsedSleepNight} for the most recent night. Pure domain: the only input
 * beyond the dump is the user's timezone, which the provider's local date strings omit.
 *
 * <p>The pipeline, per the sanctioned heuristic (Daniel, 2026-07-24):
 * <ol>
 *   <li><b>Parse</b> each sample's local {@code start}/{@code end} in the user's zone. Apple emits the
 *       hour in {@code d/M/yyyy 'at' h:mm a} form with a U+202F narrow no-break space before AM/PM,
 *       which is normalized to a plain space first. An unparseable sample or unknown stage is skipped
 *       (tolerant reader), never fatal.</li>
 *   <li><b>Cluster</b> the samples into sessions, splitting whenever a sample starts more than
 *       {@link #DEFAULT_SESSION_GAP} after the running end of the current session, and keep the most
 *       recent session (the last night).</li>
 *   <li><b>Union</b> the intervals of each stage within that session and sum them — Apple Watch revises
 *       stages, so intervals of the same stage overlap and a naive sum would double-count.</li>
 *   <li><b>Window</b> from the session's earliest start to its latest end (time in bed for the
 *       efficiency baseline).</li>
 * </ol>
 *
 * <p>Throws {@link IllegalArgumentException} when no usable night can be built (no parseable samples,
 * or a degenerate zero-length window); the caller logs it and proceeds with the replan (the sleep is
 * enrichment, not the primary action).
 */
public class SleepSampleSessionParser {

    /**
     * A gap larger than this between the running session end and the next sample's start starts a new
     * session. Three hours separates distinct nights while tolerating long mid-night awake stretches.
     */
    public static final Duration DEFAULT_SESSION_GAP = Duration.ofHours(3);

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
     * Parses the most recent night out of the dump.
     *
     * @param dump the raw sleep-stage dump; never null
     * @param zone the user's timezone, applied to the provider's zone-less local strings; never null
     * @return the aggregated night and its collection instant
     * @throws IllegalArgumentException when no scorable night can be built from the dump
     */
    public ParsedSleepNight parse(DeviceSleepSamples dump, ZoneId zone) {
        if (dump == null || zone == null) {
            throw new IllegalArgumentException("sleep dump and zone are required");
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

        List<StageInterval> session = mostRecentSession(parsed);
        SleepStageSample sample = aggregate(session);
        return new ParsedSleepNight(sample, parseInstant(dump.capturedAt(), zone));
    }

    /** Splits the samples into sessions by the gap threshold and returns the latest one. */
    private List<StageInterval> mostRecentSession(List<StageInterval> intervals) {
        intervals.sort(Comparator.comparing(StageInterval::start));
        List<StageInterval> current = new ArrayList<>();
        OffsetDateTime runningEnd = null;
        for (StageInterval interval : intervals) {
            if (runningEnd != null && Duration.between(runningEnd, interval.start()).compareTo(sessionGap) > 0) {
                current = new ArrayList<>();
                runningEnd = null;
            }
            current.add(interval);
            if (runningEnd == null || interval.end().isAfter(runningEnd)) {
                runningEnd = interval.end();
            }
        }
        return current;
    }

    /** Unions each stage's intervals within the session and derives the in-bed window. */
    private SleepStageSample aggregate(List<StageInterval> session) {
        Map<StageCategory, List<StageInterval>> byStage = new EnumMap<>(StageCategory.class);
        OffsetDateTime windowStart = null;
        OffsetDateTime windowEnd = null;
        for (StageInterval interval : session) {
            byStage.computeIfAbsent(interval.category(), key -> new ArrayList<>()).add(interval);
            if (windowStart == null || interval.start().isBefore(windowStart)) {
                windowStart = interval.start();
            }
            if (windowEnd == null || interval.end().isAfter(windowEnd)) {
                windowEnd = interval.end();
            }
        }
        return new SleepStageSample(
            windowStart, windowEnd,
            unionSeconds(byStage.get(StageCategory.IN_BED)),
            unionSeconds(byStage.get(StageCategory.CORE)),
            unionSeconds(byStage.get(StageCategory.DEEP)),
            unionSeconds(byStage.get(StageCategory.REM)),
            unionSeconds(byStage.get(StageCategory.UNSPECIFIED)),
            unionSeconds(byStage.get(StageCategory.AWAKE)));
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

    /** The HealthKit stage buckets the scorer understands; unknown labels map to none (skipped). */
    private enum StageCategory {
        CORE, DEEP, REM, AWAKE, IN_BED, UNSPECIFIED;

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
