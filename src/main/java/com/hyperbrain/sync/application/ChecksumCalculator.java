package com.hyperbrain.sync.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Calculates the SHA-256 checksum used by the sync pipeline to detect duplicate Apple events.
 *
 * <p>Contract (HU-09): {@code SHA-256(entityId + operation + canonicalPayload)}, where
 * {@code canonicalPayload} is the raw JSON string received from the SQS message.
 *
 * <p>Thread-safe: {@link MessageDigest} instances are allocated per call because they are not
 * thread-safe, but {@link MessageDigest#getInstance} is a cheap factory backed by a pool.
 */
final class ChecksumCalculator {

    private ChecksumCalculator() {}

    /**
     * Returns the lower-case hex-encoded SHA-256 of {@code entityId + operation + payload}.
     *
     * @param entityId  the EventKit identifier of the entity
     * @param operation the operation string (e.g. {@code "CREATED"})
     * @param payload   raw JSON payload string (may be null; treated as empty string)
     * @return 64-character lower-case hex string
     */
    static String compute(String entityId, String operation, String payload) {
        String input = entityId + operation + (payload != null ? payload : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JVM specification; this never happens.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
