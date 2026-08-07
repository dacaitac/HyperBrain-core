package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RetimingBand — the stretch of the day a block may be retimed within")
class RetimingBandTest {

    private static final OffsetDateTime NINE = at(9);
    private static final OffsetDateTime ELEVEN = at(11);

    private final RetimingBand band = new RetimingBand("Meta de la mañana", NINE, ELEVEN);

    @Test
    @DisplayName("a window inside the band is contained")
    void an_inner_window_is_contained() {
        assertThat(band.contains(at(9, 30), at(10, 30))).isTrue();
    }

    @Test
    @DisplayName("a window flush with the band's edges is contained — the edges are inside")
    void a_flush_window_is_contained() {
        assertThat(band.contains(NINE, ELEVEN)).isTrue();
    }

    @Test
    @DisplayName("a window that starts before the band or ends after it is not contained")
    void an_overflowing_window_is_not_contained() {
        assertThat(band.contains(at(8, 30), at(10, 0))).isFalse();
        assertThat(band.contains(at(10, 0), at(11, 30))).isFalse();
        assertThat(band.contains(at(19, 0), at(20, 0))).isFalse();
    }

    @Test
    @DisplayName("spanning widens the band and never narrows it")
    void spanning_only_widens() {
        // When: absorbing a meal's plausible hours that reach earlier and later
        RetimingBand widened = band.spanning(at(8, 0), at(12, 0));

        // Then
        assertThat(widened.start()).isEqualTo(at(8, 0));
        assertThat(widened.end()).isEqualTo(at(12, 0));
        assertThat(widened.label()).isEqualTo("Meta de la mañana");

        // And: a narrower span leaves the band untouched — the floor's own placement must stay inside
        RetimingBand unchanged = band.spanning(at(9, 30), at(10, 0));
        assertThat(unchanged.start()).isEqualTo(NINE);
        assertThat(unchanged.end()).isEqualTo(ELEVEN);
    }

    @Test
    @DisplayName("clamping narrows the band to the run's planning bounds")
    void clamping_narrows_to_the_planning_bounds() {
        // When: a replan whose lower bound is 10:00
        RetimingBand clamped = band.clampedTo(at(10, 0), at(23, 0));

        // Then: the band can no longer authorize a move into the hours already gone
        assertThat(clamped.start()).isEqualTo(at(10, 0));
        assertThat(clamped.end()).isEqualTo(ELEVEN);
    }

    @Test
    @DisplayName("a band entirely outside the planning bounds clamps to nothing")
    void a_band_outside_the_bounds_clamps_to_null() {
        assertThat(band.clampedTo(at(12, 0), at(23, 0))).isNull();
    }

    @Test
    @DisplayName("rejects a blank label and a non-positive band")
    void rejects_invalid_bands() {
        assertThatThrownBy(() -> new RetimingBand(" ", NINE, ELEVEN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetimingBand("Casa", ELEVEN, NINE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetimingBand("Casa", NINE, NINE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static OffsetDateTime at(int hour) {
        return at(hour, 0);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(2026, 8, 8, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
