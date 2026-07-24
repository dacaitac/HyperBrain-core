package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A complete device sleep record (ADR-016 {@code APPLE_HEALTH/SLEEP_SESSION}) ready to be persisted
 * as a {@code tel_sleep_record} row: real bedtime/wake instants, a computed per-night score and the
 * stage breakdown. Distinct from a manual score marker ({@code end_time IS NULL}): a device record
 * carries real hours and therefore governs its day (device precedence).
 *
 * <p>{@code contextEventId} traces the record back to the raw {@code context_event} row it was
 * normalized from. It is present for records that land through the raw-first telemetry pipeline, and
 * {@code null} for the provisional user-command sleep bridge (HealthKit sleep arriving inline on a
 * {@code REPLAN_AGENDA} command), which never lands a raw row — the {@code context_event_id} column
 * and its FK are nullable ({@code ON DELETE SET NULL}) precisely to allow both origins.
 *
 * @param startTime       bedtime (session start); never null
 * @param endTime         wake (session end); never null — this makes the record device-owned
 * @param durationMinutes total sleep time in minutes
 * @param sleepScore      the per-night score in {@code [0, 100]}
 * @param stagesJson      the stage breakdown + derived metrics as a JSON string (stored as JSONB)
 * @param collectedAt     when the collector captured the record
 * @param contextEventId  the raw {@code context_event} row this was normalized from; null when the
 *                        record has no raw origin (user-command sleep bridge)
 */
public record DeviceSleepRecord(
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    int durationMinutes,
    int sleepScore,
    String stagesJson,
    OffsetDateTime collectedAt,
    UUID contextEventId
) {

    public DeviceSleepRecord {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("device sleep record requires start and end instants");
        }
    }
}
