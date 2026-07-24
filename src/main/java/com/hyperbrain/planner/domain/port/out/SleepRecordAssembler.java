package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepStageSample;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Assembles a scored {@link DeviceSleepRecord} from a raw {@link SleepStageSample} (ADR-016 v1.4.0):
 * scores the night with the pure {@code SleepScoreCalculator}, derives the duration, and serializes
 * the stage breakdown + sub-scores for {@code tel_sleep_record.stages}.
 *
 * <p>This is the single seam shared by both device-sleep origins so the two never drift:
 * <ul>
 *   <li>the raw-first telemetry pipeline ({@code APPLE_HEALTH/SLEEP_SESSION} → context_event →
 *       normalization), which passes the raw row's id as {@code contextEventId}; and</li>
 *   <li>the provisional user-command sleep bridge (HealthKit sleep inlined on a {@code REPLAN_AGENDA}
 *       command), which has no raw row and passes {@code null}.</li>
 * </ul>
 *
 * <p>It is modelled as an out-port because the stage-breakdown serialization is an infrastructure
 * concern (JSON), keeping the {@code planner.domain} package free of that dependency while both
 * callers depend only on this abstraction.
 */
public interface SleepRecordAssembler {

    /**
     * Scores the sample and builds the persistable device record.
     *
     * @param sample         the night's stage durations and window; never null
     * @param collectedAt    when the record was captured/reported; recorded as {@code collected_at}
     * @param contextEventId the raw {@code context_event} row's id, or {@code null} for a record with
     *                       no raw origin (user-command sleep bridge)
     * @return the scored device record ready for {@code SleepScoreStore#upsertDeviceSleepRecord}
     * @throws IllegalArgumentException when the sample is not scorable (no asleep time)
     */
    DeviceSleepRecord assemble(SleepStageSample sample, OffsetDateTime collectedAt, UUID contextEventId);
}
