package com.hyperbrain.planner.domain.model;

import java.util.List;

/**
 * Raw HealthKit sleep dump forwarded inline on a {@code REPLAN_AGENDA} command (provisional bridge):
 * the iOS Shortcut cannot aggregate, so it emits every sleep-stage {@link Sample} verbatim, across
 * potentially several nights and with the overlapping intervals Apple Watch produces when it revises
 * stages. {@code capturedAt} is when the Shortcut ran (the collection instant).
 *
 * <p>This is the un-interpreted wire form: stage labels and local date strings are kept as-is. Turning
 * it into a single scorable night — most-recent-session selection, per-stage interval union, window
 * derivation and local-time parsing — is the {@code SleepSampleSessionParser}'s job (the anti-corruption
 * boundary), which needs the user's timezone the strings lack.
 *
 * @param capturedAt the Shortcut's capture timestamp as a local string, or null when absent
 * @param samples    the stage samples; never null (empty when none were reported)
 */
public record DeviceSleepSamples(String capturedAt, List<Sample> samples) {

    public DeviceSleepSamples {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /**
     * One HealthKit sleep-stage interval as reported: a stage label and its local start/end strings.
     * The {@code duration} the provider ships is intentionally not carried — seconds are derived from
     * the parsed start/end (needed anyway for clustering and interval union), never from that string.
     *
     * @param stage the stage label (e.g. {@code Core}, {@code Deep}, {@code REM}, {@code Awake}, {@code In Bed})
     * @param start the interval start as a local date-time string
     * @param end   the interval end as a local date-time string
     */
    public record Sample(String stage, String start, String end) {
    }
}
