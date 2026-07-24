package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;

/**
 * The single scorable night distilled from a {@link DeviceSleepSamples} dump: the aggregated
 * {@link SleepStageSample} (most-recent session, per-stage interval union) ready for scoring, plus the
 * collection instant to record as {@code collected_at}.
 *
 * @param sample      the aggregated stage sample for the most recent night; never null
 * @param collectedAt the Shortcut's capture instant, or null when it was absent/unparseable (the caller
 *                    then falls back to the command's {@code occurred_at})
 */
public record ParsedSleepNight(SleepStageSample sample, OffsetDateTime collectedAt) {

    public ParsedSleepNight {
        if (sample == null) {
            throw new IllegalArgumentException("parsed sleep night requires a stage sample");
        }
    }
}
