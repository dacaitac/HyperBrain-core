package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One sleep day a {@link DeviceSleepSamples} dump yielded: the night plus whatever naps followed it,
 * already aggregated and ready to be scored and stored, together with the night's own raw samples and
 * the instant the dump was captured.
 *
 * @param sleepDay    the local day this sleep belongs to — the day he woke into; never null
 * @param samples     the dump's samples that belong to this night, as they arrived; never null
 * @param sleep       the day's sessions summed into one scorable aggregate; never null
 * @param collectedAt when the dump was captured; never null (the parser falls back to the caller's
 *                    reference instant when the dump carries no usable capture date)
 */
public record ParsedSleepDay(LocalDate sleepDay, DeviceSleepSamples samples, AggregatedSleep sleep,
                             OffsetDateTime collectedAt) implements SleepDayReading {

    public ParsedSleepDay {
        if (sleepDay == null || samples == null || sleep == null || collectedAt == null) {
            throw new IllegalArgumentException(
                "parsed sleep day requires a day, its samples, an aggregate and a capture instant");
        }
    }
}
