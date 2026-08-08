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
    @DisplayName("spanning widens one edge at a time when the span overlaps the band only partially")
    void spanning_widens_edge_by_edge() {
        // A meal that may start earlier than its band but must end sooner than the band's own end —
        // the union takes the outer edge on each side independently.
        RetimingBand earlier = band.spanning(at(8, 0), at(10, 0));
        assertThat(earlier.start()).isEqualTo(at(8, 0));
        assertThat(earlier.end()).isEqualTo(ELEVEN);

        RetimingBand later = band.spanning(at(10, 0), at(12, 30));
        assertThat(later.start()).isEqualTo(NINE);
        assertThat(later.end()).isEqualTo(at(12, 30));
    }

    @Test
    @DisplayName("spanning a disjoint stretch swallows the gap rather than splitting the band")
    void spanning_a_disjoint_stretch_swallows_the_gap() {
        // A band is one contiguous stretch by definition, so absorbing hours that do not touch it
        // widens across the gap. Documented here because it is the shape a badly configured meal band
        // would produce, and it is permissive — never narrowing.
        RetimingBand widened = band.spanning(at(14, 0), at(15, 0));

        assertThat(widened.start()).isEqualTo(NINE);
        assertThat(widened.end()).isEqualTo(at(15, 0));
    }

    @Test
    @DisplayName("spanning is idempotent: absorbing the band's own edges changes nothing")
    void spanning_its_own_edges_changes_nothing() {
        assertThat(band.spanning(NINE, ELEVEN)).usingRecursiveComparison().isEqualTo(band);
    }

    @Test
    @DisplayName("clamping to bounds that already contain the band leaves it untouched")
    void clamping_to_wider_bounds_changes_nothing() {
        assertThat(band.clampedTo(at(6, 0), at(23, 0))).usingRecursiveComparison().isEqualTo(band);
    }

    @Test
    @DisplayName("a band whose whole stretch has gone by clamps to nothing, edges included")
    void a_band_ending_exactly_at_the_lower_bound_clamps_to_null() {
        // The replan lands exactly on the band's closing instant: nothing of it is left to move within.
        assertThat(band.clampedTo(ELEVEN, at(23, 0))).isNull();
        assertThat(band.clampedTo(at(6, 0), NINE)).isNull();
    }

    @Test
    @DisplayName("the label is stripped, so a padded configuration value never reaches the day")
    void the_label_is_stripped() {
        assertThat(new RetimingBand("  Casa  ", NINE, ELEVEN).label()).isEqualTo("Casa");
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

    @Test
    @DisplayName("rejects a band with a missing edge — an open-ended band would bound nothing")
    void rejects_a_band_with_a_missing_edge() {
        assertThatThrownBy(() -> new RetimingBand("Casa", null, ELEVEN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetimingBand("Casa", NINE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static OffsetDateTime at(int hour) {
        return at(hour, 0);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(2026, 8, 8, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
