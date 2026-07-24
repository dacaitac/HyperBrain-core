package com.hyperbrain.planner.infrastructure.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the semantic {@code dedup_key} for a raw telemetry row (ADR-016): {@code provider:event_type:}
 * followed by the SHA-256 of the canonical payload text. The transport-level {@code event_id} covers
 * SQS at-least-once retries; this key catches semantic duplicates across re-sends that carry a fresh
 * {@code event_id} but the same fact.
 *
 * <p>Since the payload is opaque to the collector-agnostic consumer, the content hash is the sanctioned
 * fallback of the "provider + external id, or a content hash" rule — a provider-specific external-id
 * extraction can be added later per strategy without changing this key's shape.
 */
final class TelemetryDedupKey {

    private TelemetryDedupKey() {
    }

    /**
     * Computes the dedup key for an envelope.
     *
     * @param provider    the data origin (may be null → empty)
     * @param eventType   the type within the provider (may be null → empty)
     * @param payloadJson the canonical payload JSON text (never null)
     * @return {@code provider:eventType:<sha-256 hex>}
     */
    static String of(String provider, String eventType, String payloadJson) {
        return nullToEmpty(provider) + ':' + nullToEmpty(eventType) + ':' + sha256Hex(payloadJson);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JVM specification; this never happens.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
