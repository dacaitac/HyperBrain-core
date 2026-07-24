package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepScoreResult;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.requiredInstant;
import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.seconds;

/**
 * Normalizes {@code APPLE_HEALTH/SLEEP_SESSION} into a device {@code tel_sleep_record} row (ADR-016).
 * Computes the per-night {@code sleep_score} with the pure {@link SleepScoreCalculator} and persists a
 * complete device record through {@link SleepScoreStore#upsertDeviceSleepRecord}, which enforces
 * device precedence and the frontier invariant already codified in the store — no synthetic-hour
 * markers are created.
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

    private static final double SECONDS_PER_MINUTE = 60.0;

    private final SleepScoreCalculator calculator;
    private final SleepScoreStore sleepScoreStore;
    private final ObjectMapper objectMapper;

    SleepSessionNormalizationStrategy(SleepScoreCalculator calculator, SleepScoreStore sleepScoreStore,
                                      ObjectMapper objectMapper) {
        this.calculator = calculator;
        this.sleepScoreStore = sleepScoreStore;
        this.objectMapper = objectMapper;
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

        SleepScoreResult result = calculator.score(sample);
        int durationMinutes = (int) Math.round(sample.totalSleepSeconds() / SECONDS_PER_MINUTE);
        DeviceSleepRecord deviceRecord = new DeviceSleepRecord(
            start, end, durationMinutes, result.score(), stagesJson(sample, result),
            record.collectedAt(), record.contextEventId());

        sleepScoreStore.upsertDeviceSleepRecord(record.userId(), deviceRecord, record.zone());
    }

    /** Serializes the stage durations, derived metrics and sub-scores for {@code tel_sleep_record.stages}. */
    private String stagesJson(SleepStageSample sample, SleepScoreResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("in_bed_seconds", sample.inBedSeconds());
        root.put("core_seconds", sample.coreSeconds());
        root.put("deep_seconds", sample.deepSeconds());
        root.put("rem_seconds", sample.remSeconds());
        root.put("unspecified_seconds", sample.unspecifiedSeconds());
        root.put("awake_seconds", sample.awakeSeconds());
        root.put("tst_hours", result.tstHours());
        root.put("efficiency", result.efficiency());
        root.put("deep_fraction", result.deepFraction());
        root.put("rem_fraction", result.remFraction());
        root.put("waso_minutes", result.wasoMinutes());
        root.put("low_confidence", result.lowConfidence());
        ObjectNode subScores = root.putObject("sub_scores");
        subScores.put("duration", result.durationSubScore());
        subScores.put("efficiency", result.efficiencySubScore());
        subScores.put("deep", result.deepSubScore());
        subScores.put("rem", result.remSubScore());
        subScores.put("waso", result.wasoSubScore());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            // The node is built from primitives and boxed doubles; serialization cannot fail.
            throw new IllegalStateException("Unserializable sleep stages node", ex);
        }
    }
}
