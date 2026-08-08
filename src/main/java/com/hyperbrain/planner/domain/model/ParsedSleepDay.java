package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;

/**
 * What a {@link DeviceSleepSamples} dump yields: the sleep of the current sleep day — the night plus
 * whatever naps followed it — already aggregated and ready to be scored and stored, together with the
 * instant the dump was captured.
 *
 * @param sleep       the period's sessions summed into one scorable aggregate; never null
 * @param collectedAt when the dump was captured; never null (the parser falls back to the caller's
 *                    reference instant when the dump carries no usable capture date)
 */
public record ParsedSleepDay(AggregatedSleep sleep, OffsetDateTime collectedAt) {

    public ParsedSleepDay {
        if (sleep == null || collectedAt == null) {
            throw new IllegalArgumentException("parsed sleep day requires an aggregate and a capture instant");
        }
    }
}
