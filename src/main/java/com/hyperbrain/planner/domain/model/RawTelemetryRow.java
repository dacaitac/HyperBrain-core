package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The raw telemetry envelope as it lands in {@code context_event} (ADR-016 raw-first): the opaque
 * provider payload plus the routing/idempotency metadata, before any interpretation. Persisted with
 * {@code normalization_status = PENDING} and {@code source = INTEGRATION}.
 *
 * @param userId        the owning user (single-user MVP default); never null
 * @param provider      data origin, e.g. {@code APPLE_HEALTH}; may be null (then normalized as SKIPPED)
 * @param eventType     type within the provider, e.g. {@code SLEEP_SESSION}; may be null
 * @param schemaVersion provider payload format version (tolerant reader); may be null
 * @param payloadJson   the raw provider payload as JSON text, stored verbatim as JSONB; never null
 * @param occurredAt    when the measured fact happened; never null
 * @param dedupKey      semantic idempotency key (provider + external id, or a content hash); never null
 */
public record RawTelemetryRow(
    UUID userId,
    String provider,
    String eventType,
    String schemaVersion,
    String payloadJson,
    OffsetDateTime occurredAt,
    String dedupKey
) {

    public RawTelemetryRow {
        if (userId == null) {
            throw new IllegalArgumentException("raw telemetry row requires a user id");
        }
        if (payloadJson == null) {
            throw new IllegalArgumentException("raw telemetry row requires a payload");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("raw telemetry row requires occurred_at");
        }
        if (dedupKey == null) {
            throw new IllegalArgumentException("raw telemetry row requires a dedup_key");
        }
    }
}
