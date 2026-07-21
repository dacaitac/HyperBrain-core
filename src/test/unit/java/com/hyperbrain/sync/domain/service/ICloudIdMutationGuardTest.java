package com.hyperbrain.sync.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ICloudIdMutationGuard")
class ICloudIdMutationGuardTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-21T12:00:00-05:00");

    private final ICloudIdMutationGuard guard = new ICloudIdMutationGuard(WINDOW);

    @Test
    @DisplayName("a mapping written just inside the window is a suspected id mutation")
    void fresh_mapping_is_within_window() {
        // Given a mapping written 1 minute ago
        OffsetDateTime mappedAt = NOW.minusMinutes(1);

        // When / Then
        assertThat(guard.withinMutationWindow(mappedAt, NOW)).isTrue();
    }

    @Test
    @DisplayName("a mapping older than the window is a real deletion")
    void old_mapping_is_outside_window() {
        // Given a mapping written 11 minutes ago
        OffsetDateTime mappedAt = NOW.minusMinutes(11);

        // When / Then
        assertThat(guard.withinMutationWindow(mappedAt, NOW)).isFalse();
    }

    @Test
    @DisplayName("a mapping exactly at the window boundary is a real deletion")
    void boundary_is_outside_window() {
        // Given a mapping written exactly the window ago
        OffsetDateTime mappedAt = NOW.minus(WINDOW);

        // When / Then — age == window is not strictly less than the window
        assertThat(guard.withinMutationWindow(mappedAt, NOW)).isFalse();
    }

    @Test
    @DisplayName("a null timestamp has nothing to protect and is outside the window")
    void null_mapping_is_outside_window() {
        assertThat(guard.withinMutationWindow(null, NOW)).isFalse();
    }

    @Test
    @DisplayName("a future timestamp (clock skew) is treated conservatively as within the window")
    void future_mapping_is_within_window() {
        // Given a mapping timestamped in the future
        OffsetDateTime mappedAt = NOW.plusMinutes(1);

        // When / Then
        assertThat(guard.withinMutationWindow(mappedAt, NOW)).isTrue();
    }
}
