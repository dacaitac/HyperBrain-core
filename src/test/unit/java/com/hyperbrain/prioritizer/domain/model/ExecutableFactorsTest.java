package com.hyperbrain.prioritizer.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExecutableFactors")
class ExecutableFactorsTest {

    @Test
    @DisplayName("rejects a null executable id")
    void rejects_null_executable_id() {
        assertThatThrownBy(() -> new ExecutableFactors(null, null, 4, 3.0, 2.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("executableId");
    }

    @Test
    @DisplayName("accepts null nullable factors (missing profile data)")
    void accepts_null_optional_factors() {
        UUID id = UUID.fromString("e0000000-0000-0000-0000-0000000000ff");

        // Should not throw: impact, cycleId and effort may all be absent.
        new ExecutableFactors(id, null, null, 0.0, null);
    }
}
