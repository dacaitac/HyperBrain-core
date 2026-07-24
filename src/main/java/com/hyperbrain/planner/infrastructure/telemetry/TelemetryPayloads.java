package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Tolerant-reader helpers shared by the normalization strategies: absent or null fields collapse to a
 * sane default (0 for durations, null for optionals) so a partial payload never crashes the reader,
 * while genuinely required structural fields (session/bucket instants, category) throw a descriptive
 * {@link IllegalArgumentException} — captured by the normalizer as an ERROR on the raw row.
 */
final class TelemetryPayloads {

    private TelemetryPayloads() {
    }

    /** First present numeric field among {@code keys}, coerced to seconds; 0 when none is present. */
    static long seconds(JsonNode node, String... keys) {
        JsonNode value = firstPresent(node, keys);
        return value == null ? 0L : value.asLong(0L);
    }

    /** Integer field, or 0 when absent/null. */
    static int intValue(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? 0 : value.asInt(0);
    }

    /** Integer field, or null when absent/null (for genuinely optional metrics like pickups). */
    static Integer optionalInt(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asInt(0);
    }

    /** Required non-blank text field; throws when absent/blank. */
    static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("telemetry payload missing required field '" + key + "'");
        }
        return value.asText();
    }

    /** Required instant from the first present of {@code keys}; throws when absent or unparseable. */
    static OffsetDateTime requiredInstant(JsonNode node, String... keys) {
        JsonNode value = firstPresent(node, keys);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("telemetry payload missing required instant " + String.join("/", keys));
        }
        try {
            return OffsetDateTime.parse(value.asText());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("telemetry payload has an unparseable instant "
                + String.join("/", keys) + ": " + value.asText(), ex);
        }
    }

    private static JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }
}
