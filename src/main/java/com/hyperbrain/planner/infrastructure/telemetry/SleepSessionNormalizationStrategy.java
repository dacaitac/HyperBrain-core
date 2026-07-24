package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.port.out.SleepRecordAssembler;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.requiredInstant;
import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.seconds;

/**
 * Normalizes {@code APPLE_HEALTH/SLEEP_SESSION} into a device {@code tel_sleep_record} row (ADR-016).
 * Reads the raw payload into a {@link SleepStageSample}, delegates the scoring and stage-breakdown
 * serialization to the shared {@link SleepRecordAssembler}, and persists the resulting device record
 * through {@link SleepScoreStore#upsertDeviceSleepRecord}, which enforces device precedence and the
 * frontier invariant already codified in the store — no synthetic-hour markers are created.
 *
 * <p><b>Expected payload</b> (tolerant reader; missing stage durations default to 0, aliases accepted):
 * <pre>{@code
 * { "start_time": "...", "end_time": "...",
 *   "in_bed_seconds": N, "core_seconds": N, "deep_seconds": N,
 *   "rem_seconds": N, "unspecified_seconds": N, "awake_seconds": N }
 * }</pre>
 * {@code start_time}/{@code end_time} are required (the row's real hours); a payload with no asleep
 * time at all is not scorable and surfaces as an ERROR. When no stage breakdown is present the
 * calculator falls back to duration + efficiency (60/40) with a low-confidence flag — never a 0 score.
 *
 * <p>The raw per-night score is written as-is; downstream EWMA smoothing over recent nights and the
 * F3/F6 mapping remain the planner's {@code EnergyResolver}'s job, untouched here.
 */
@Component
class SleepSessionNormalizationStrategy implements TelemetryNormalizationStrategy {

    static final String PROVIDER = "APPLE_HEALTH";
    static final String EVENT_TYPE = "SLEEP_SESSION";

    private final SleepRecordAssembler sleepRecordAssembler;
    private final SleepScoreStore sleepScoreStore;

    SleepSessionNormalizationStrategy(SleepRecordAssembler sleepRecordAssembler,
                                      SleepScoreStore sleepScoreStore) {
        this.sleepRecordAssembler = sleepRecordAssembler;
        this.sleepScoreStore = sleepScoreStore;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void normalize(TelemetryRecord record) {
        // Parse-then-write: everything below the store call is pure parsing/computation, so a
        // tolerant-reader failure throws before any SQL and never poisons the ingest transaction.
        JsonNode payload = record.payload();
        OffsetDateTime start = requiredInstant(payload, "start_time", "start");
        OffsetDateTime end = requiredInstant(payload, "end_time", "end");
        SleepStageSample sample = new SleepStageSample(
            start, end,
            seconds(payload, "in_bed_seconds"),
            seconds(payload, "core_seconds"),
            seconds(payload, "deep_seconds"),
            seconds(payload, "rem_seconds"),
            seconds(payload, "unspecified_seconds"),
            seconds(payload, "awake_seconds"));

        DeviceSleepRecord deviceRecord =
            sleepRecordAssembler.assemble(sample, record.collectedAt(), record.contextEventId());
        sleepScoreStore.upsertDeviceSleepRecord(record.userId(), deviceRecord, record.zone());
    }
}
