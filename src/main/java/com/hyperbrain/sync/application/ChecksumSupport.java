package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.Operation;

import java.util.Map;

/**
 * Checksum recipe shared by the Notion inbound sync (HU-14 CA-4) and compatible with the
 * outbound write-back (HU-10): {@code SHA-256(external_id + operation + propertiesJson)} over
 * the canonical Notion property map. The inbound side stores checksums with {@code UPDATED}
 * but matches against both {@code UPDATED} and {@code CREATED}, so the echo of an outbound
 * CREATE is recognized too (CA-20).
 */
final class ChecksumSupport {

    private ChecksumSupport() {
    }

    /** Computes the checksum to store after persisting one inbound page state. */
    static String compute(String externalId, Map<String, Object> canonicalProps, ObjectMapper objectMapper) {
        return ChecksumCalculator.compute(externalId, Operation.UPDATED.name(),
            propertiesJson(canonicalProps, objectMapper));
    }

    /** Returns whether the stored checksum matches the canonical props under either operation. */
    static boolean matches(String storedChecksum, String externalId,
                           Map<String, Object> canonicalProps, ObjectMapper objectMapper) {
        if (storedChecksum == null) {
            return false;
        }
        String json = propertiesJson(canonicalProps, objectMapper);
        return storedChecksum.equals(ChecksumCalculator.compute(externalId, Operation.UPDATED.name(), json))
            || storedChecksum.equals(ChecksumCalculator.compute(externalId, Operation.CREATED.name(), json));
    }

    private static String propertiesJson(Map<String, Object> props, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(props);
        } catch (JsonProcessingException ex) {
            // Property maps only contain strings, numbers and booleans; this never happens.
            throw new IllegalStateException("Unserializable Notion property map", ex);
        }
    }
}
