package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;

/**
 * A sleep day the reader could not turn into sleep: nothing parseable in the dump, or a volume of sleep
 * the plausibility guards refused. It is a first-class outcome rather than an exception because a dump
 * may hold six weeks of nights and one corrupt reading must not cost all the others.
 *
 * @param sleepDay the local day the refused reading was about; never null
 * @param samples  the night's raw samples, kept so the refusal can be diagnosed; never null
 * @param reason   why the night was refused, for the caller's log; never null nor blank
 */
public record RefusedSleepDay(LocalDate sleepDay, DeviceSleepSamples samples, String reason)
    implements SleepDayReading {

    public RefusedSleepDay {
        if (sleepDay == null || samples == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a refused sleep day requires a day, its samples and a reason");
        }
    }
}
