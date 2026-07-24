package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A raw telemetry envelope already landed in {@code context_event}, handed to the
 * {@link TelemetryNormalizer} for projection into a typed {@code tel_*} table. Carries the raw payload
 * node plus everything a strategy needs — the owning user, the source row's id (for FK traceability),
 * the collector timestamp and the user's timezone (for day resolution).
 *
 * @param userId         the owning user; never null
 * @param contextEventId the raw {@code context_event} row's id; never null
 * @param provider       data origin (e.g. {@code APPLE_HEALTH})
 * @param eventType      type within the provider (e.g. {@code SLEEP_SESSION})
 * @param schemaVersion  provider payload format version (tolerant reader)
 * @param occurredAt     when the measured fact happened; never null
 * @param collectedAt    when the collector captured it; never null
 * @param payload        the opaque provider payload; never null
 * @param zone           the user's timezone; never null
 */
record TelemetryRecord(
    UUID userId,
    UUID contextEventId,
    String provider,
    String eventType,
    String schemaVersion,
    OffsetDateTime occurredAt,
    OffsetDateTime collectedAt,
    JsonNode payload,
    ZoneId zone
) {
}
