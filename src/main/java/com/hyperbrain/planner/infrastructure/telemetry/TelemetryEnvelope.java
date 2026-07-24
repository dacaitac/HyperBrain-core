package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * The parsed {@code TelemetryEnvelope} (AsyncAPI v1.9.0) — the transport view of one telemetry
 * message. Every field beyond a syntactically valid JSON body is read leniently: a missing or
 * malformed metadata field yields {@code null} rather than aborting, because ADR-016 raw-first
 * ingestion must land the payload regardless of metadata quality (an absent {@code provider} lands
 * raw and is later SKIPPED, never dropped). Only a body that is not valid JSON at all is rejected —
 * that check lives in {@link TelemetryConsumer}.
 *
 * <p>{@link #payload} is the opaque provider object, kept as a {@link JsonNode} for the strategy of
 * its {@code (provider, event_type)} pair to interpret; it is stored verbatim as JSONB.
 */
record TelemetryEnvelope(
    UUID eventId,
    String sourceSystem,
    String provider,
    String eventType,
    String schemaVersion,
    OffsetDateTime occurredAt,
    OffsetDateTime collectedAt,
    JsonNode payload
) {

    /** Builds an envelope from a JSON tree, extracting each field leniently (never throws). */
    static TelemetryEnvelope fromTree(JsonNode root) {
        return new TelemetryEnvelope(
            uuid(root, "event_id"),
            text(root, "source_system"),
            text(root, "provider"),
            text(root, "event_type"),
            text(root, "schema_version"),
            instant(root, "occurred_at"),
            instant(root, "collected_at"),
            payloadOf(root));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID uuid(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static OffsetDateTime instant(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static JsonNode payloadOf(JsonNode root) {
        JsonNode node = root.get("payload");
        return node == null || node.isNull() ? null : node;
    }
}
