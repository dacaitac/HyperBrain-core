package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepScoreResult;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.port.out.SleepRecordAssembler;
import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jackson-backed {@link SleepRecordAssembler}: runs the pure {@link SleepScoreCalculator} over the
 * aggregate's totals and serializes the stage breakdown, the derived metrics and the day's sessions
 * into the {@code tel_sleep_record.stages} JSON. The JSON serialization is why this lives in
 * infrastructure while the port stays in the domain.
 *
 * <p>The {@code sessions} array is the only place the individual stretches survive: the row has exactly
 * two instant columns and they are spoken for by the night's real span (the chronotype the sleep
 * frontier learns from), so an afternoon nap — or the hole in a broken night — can only be recorded
 * beside them, not in them.
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
    public DeviceSleepRecord assemble(AggregatedSleep sleep, OffsetDateTime collectedAt,
                                      UUID contextEventId) {
        SleepStageSample totals = sleep.totals();
        SleepScoreResult result = calculator.score(totals);
        int durationMinutes = (int) Math.round(totals.totalSleepSeconds() / SECONDS_PER_MINUTE);
        return new DeviceSleepRecord(
            sleep.night().start(), sleep.night().end(), durationMinutes, result.score(),
            stagesJson(sleep, result), collectedAt, contextEventId);
    }

    /**
     * Serializes the summed stage durations, the overlap measurement, the derived metrics, the
     * sub-scores and the sessions the totals were summed from, for {@code tel_sleep_record.stages}.
     */
    private String stagesJson(AggregatedSleep sleep, SleepScoreResult result) {
        SleepStageSample sample = sleep.totals();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("in_bed_seconds", sample.inBedSeconds());
        root.put("core_seconds", sample.coreSeconds());
        root.put("deep_seconds", sample.deepSeconds());
        root.put("rem_seconds", sample.remSeconds());
        root.put("unspecified_seconds", sample.unspecifiedSeconds());
        root.put("awake_seconds", sample.awakeSeconds());
        // Not a duration of the night but a measurement of the reading: how much of the sleep more
        // than one stage claimed at once, which is what the proportional split had to divide. Kept on
        // the row so the stage mix of past nights can be judged, and the score recalibrated, against
        // how contested the data behind it was.
        root.put("overlap_seconds", sleep.overlapSeconds());
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
        ArrayNode sessions = root.putArray("sessions");
        for (SleepSession session : sleep.sessions()) {
            ObjectNode node = sessions.addObject();
            node.put("start", session.start().toString());
            node.put("end", session.end().toString());
            node.put("asleep_seconds", session.asleepSeconds());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            // The node is built from primitives and boxed doubles; serialization cannot fail.
            throw new IllegalStateException("Unserializable sleep stages node", ex);
        }
    }
}
