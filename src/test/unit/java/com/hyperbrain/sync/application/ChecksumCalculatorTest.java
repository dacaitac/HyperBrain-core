package com.hyperbrain.sync.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChecksumCalculator")
class ChecksumCalculatorTest {

    @Test
    @DisplayName("produces a 64-character lower-case hex string")
    void produces_hex_sha256() {
        String result = ChecksumCalculator.compute("EKReminder-abc", "CREATED", "{\"title\":\"test\"}");
        assertThat(result).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("same inputs produce the same checksum (deterministic)")
    void deterministic_for_same_input() {
        String a = ChecksumCalculator.compute("EKReminder-abc", "CREATED", "{\"title\":\"test\"}");
        String b = ChecksumCalculator.compute("EKReminder-abc", "CREATED", "{\"title\":\"test\"}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("different payloads produce different checksums")
    void different_payloads_differ() {
        String a = ChecksumCalculator.compute("EKReminder-abc", "CREATED", "{\"title\":\"foo\"}");
        String b = ChecksumCalculator.compute("EKReminder-abc", "CREATED", "{\"title\":\"bar\"}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("different operations produce different checksums")
    void different_operations_differ() {
        String created = ChecksumCalculator.compute("EKReminder-abc", "CREATED", "{\"title\":\"x\"}");
        String updated = ChecksumCalculator.compute("EKReminder-abc", "UPDATED", "{\"title\":\"x\"}");
        assertThat(created).isNotEqualTo(updated);
    }

    @Test
    @DisplayName("null payload is treated as empty string (no NPE)")
    void null_payload_no_npe() {
        assertThat(ChecksumCalculator.compute("EKReminder-abc", "DELETED", null))
            .hasSize(64);
    }
}
