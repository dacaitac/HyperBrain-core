package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;

/**
 * Raw sleep-session durations extracted from a device provider payload (ADR-016
 * {@code APPLE_HEALTH/SLEEP_SESSION}), before any scoring. All durations are in seconds and default
 * to {@code 0} when the provider omits a stage — the calculator interprets absence, never the reader.
 *
 * <p>Stage vocabulary follows {@code HKCategoryValueSleepAnalysis}: {@code core} (≈ N1+N2),
 * {@code deep} (≈ N3), {@code rem}, {@code unspecified} (asleep, stage not classified) and
 * {@code awake} (time awake during the in-bed window). {@code inBed} is the residual in-bed time the
 * watch did not classify; it is retained for traceability but the time-in-bed baseline the efficiency
 * sub-score uses is the session window ({@code end - start}), which is robust to missing segments.
 *
 * @param start           bedtime instant (session start); never null
 * @param end             wake instant (session end); never null and after {@code start}
 * @param inBedSeconds    unclassified in-bed seconds
 * @param coreSeconds     core (N1+N2) seconds
 * @param deepSeconds     deep (N3) seconds
 * @param remSeconds      REM seconds
 * @param unspecifiedSeconds asleep seconds with no stage classification (legacy/low-fidelity data)
 * @param awakeSeconds    awake-in-bed seconds (feeds WASO)
 */
public record SleepStageSample(
    OffsetDateTime start,
    OffsetDateTime end,
    long inBedSeconds,
    long coreSeconds,
    long deepSeconds,
    long remSeconds,
    long unspecifiedSeconds,
    long awakeSeconds
) {

    public SleepStageSample {
        if (start == null || end == null) {
            throw new IllegalArgumentException("sleep sample requires start and end instants");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("sleep sample end must be after start: " + start + " .. " + end);
        }
        if (inBedSeconds < 0 || coreSeconds < 0 || deepSeconds < 0 || remSeconds < 0
            || unspecifiedSeconds < 0 || awakeSeconds < 0) {
            throw new IllegalArgumentException("sleep sample durations must be non-negative");
        }
    }

    /** Total sleep time (TST): the sum of all asleep segments, in seconds. */
    public long totalSleepSeconds() {
        return coreSeconds + deepSeconds + remSeconds + unspecifiedSeconds;
    }

    /**
     * Whether the provider classified sleep into stages. When false the score falls back to a
     * duration + efficiency computation with a low-confidence flag (ADR-016 robustness rule): a
     * missing stage breakdown never zeroes the score.
     */
    public boolean hasPhaseBreakdown() {
        return coreSeconds > 0 || deepSeconds > 0 || remSeconds > 0;
    }
}
