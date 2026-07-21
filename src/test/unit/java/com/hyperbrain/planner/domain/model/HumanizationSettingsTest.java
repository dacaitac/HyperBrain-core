package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HumanizationSettings (H1 calibration)")
class HumanizationSettingsTest {

    @Test
    @DisplayName("the sanctioned defaults carry the ratified MVP values")
    void defaults_carry_sanctioned_values() {
        HumanizationSettings defaults = HumanizationSettings.DEFAULT;

        assertThat(defaults.transitionBufferMinutes()).isEqualTo(5);
        assertThat(defaults.minBlockMinutes()).isEqualTo(15);
        assertThat(defaults.batchBandWidth()).isEqualTo(0.10);
        assertThat(defaults.occupancyMinFraction()).isEqualTo(0.75);
        assertThat(defaults.occupancyMaxFraction()).isEqualTo(0.85);
        assertThat(defaults.mealWindows()).extracting(MealWindow::label)
            .containsExactly("lunch", "dinner");
    }

    @Test
    @DisplayName("the no-op instance leaves placement untouched (no buffer, no meals, full-window cap)")
    void no_op_is_neutral() {
        HumanizationSettings noOp = HumanizationSettings.NO_OP;

        assertThat(noOp.transitionBufferMinutes()).isZero();
        assertThat(noOp.minBlockMinutes()).isZero();
        assertThat(noOp.batchBandWidth()).isZero();
        assertThat(noOp.mealWindows()).isEmpty();
        assertThat(noOp.occupancyMaxFraction()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejects an occupancy max below the min")
    void rejects_inverted_occupancy_band() {
        assertThatThrownBy(() -> new HumanizationSettings(5, List.of(), 15, 0.10, 0.85, 0.75))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("occupancyMaxFraction");
    }

    @Test
    @DisplayName("rejects a negative transition buffer")
    void rejects_negative_buffer() {
        assertThatThrownBy(() -> new HumanizationSettings(-1, List.of(), 15, 0.10, 0.75, 0.85))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a batching band outside [0, 1]")
    void rejects_out_of_range_band() {
        assertThatThrownBy(() -> new HumanizationSettings(5, List.of(), 15, 1.5, 0.75, 0.85))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
