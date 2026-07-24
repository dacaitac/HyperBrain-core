package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepScoreResult;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.port.out.SleepRecordAssembler;
import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jackson-backed {@link SleepRecordAssembler}: runs the pure {@link SleepScoreCalculator} over the
 * sample and serializes the stage breakdown + derived metrics into the {@code tel_sleep_record.stages}
 * JSON. The JSON serialization is why this lives in infrastructure while the port stays in the domain.
 */
@Component
class JacksonSleepRecordAssembler implements SleepRecordAssembler {

    private static final double SECONDS_PER_MINUTE = 60.0;

    private final SleepScoreCalculator calculator;
    private final ObjectMapper objectMapper;

    JacksonSleepRecordAssembler(SleepScoreCalculator calculator, ObjectMapper objectMapper) {
        this.calculator = calculator;
        this.objectMapper = objectMapper;
    }

    @Override
    public DeviceSleepRecord assemble(SleepStageSample sample, OffsetDateTime collectedAt,
                                      UUID contextEventId) {
        SleepScoreResult result = calculator.score(sample);
        int durationMinutes = (int) Math.round(sample.totalSleepSeconds() / SECONDS_PER_MINUTE);
        return new DeviceSleepRecord(
            sample.start(), sample.end(), durationMinutes, result.score(),
            stagesJson(sample, result), collectedAt, contextEventId);
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
