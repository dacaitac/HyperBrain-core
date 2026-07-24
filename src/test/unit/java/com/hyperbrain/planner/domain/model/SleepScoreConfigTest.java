package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SleepScoreConfig (calibration invariants)")
class SleepScoreConfigTest {

    @Test
    @DisplayName("the sanctioned defaults have weights summing to 1.0")
    void defaults_weights_sum_to_one() {
        SleepScoreConfig config = SleepScoreConfig.defaults();

        double sum = config.durationWeight() + config.efficiencyWeight() + config.deepWeight()
            + config.remWeight() + config.wasoWeight();
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("weights that do not sum to 1.0 are rejected")
    void weights_must_sum_to_one() {
        assertThatThrownBy(() -> new SleepScoreConfig(
            0.50, 0.30, 0.10, 0.10, 0.05,
            3.0, 7.0, 10.0, 15.0, 0.75, 0.90,
            0.02, 0.13, 0.23, 0.35, 0.10, 0.20, 0.25, 0.35, 20.0, 60.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weights must sum to 1.0");
    }

    @Test
    @DisplayName("out-of-order breakpoints are rejected")
    void breakpoints_must_be_ordered() {
        assertThatThrownBy(() -> new SleepScoreConfig(
            0.45, 0.30, 0.10, 0.10, 0.05,
            8.0, 7.0, 10.0, 15.0, 0.75, 0.90,
            0.02, 0.13, 0.23, 0.35, 0.10, 0.20, 0.25, 0.35, 20.0, 60.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duration window");
    }

    @Test
    @DisplayName("no-phase weight shares are the 45/30 ratio → 60/40")
    void no_phase_weight_shares() {
        SleepScoreConfig config = SleepScoreConfig.defaults();

        assertThat(config.durationWeightNoPhase()).isCloseTo(0.6, within(1e-9));
        assertThat(config.efficiencyWeightNoPhase()).isCloseTo(0.4, within(1e-9));
    }
}
