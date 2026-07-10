package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SettledObservation (spike #63)")
class SettledObservationTest {

    @Test
    @DisplayName("unit cost divides actual minutes by the imputed-subtask count")
    void unit_cost_divides_minutes_by_count() {
        SettledObservation observation = new SettledObservation(90, 4);

        assertThat(observation.isValid()).isTrue();
        assertThat(observation.unitCost()).isCloseTo(22.5, within(1e-9));
    }

    @Test
    @DisplayName("a block with no imputed subtask is invalid and yields no unit cost")
    void zero_imputed_count_is_invalid() {
        SettledObservation observation = new SettledObservation(90, 0);

        assertThat(observation.isValid()).isFalse();
        assertThatThrownBy(observation::unitCost)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("negative actual minutes are rejected at construction")
    void negative_actual_minutes_rejected() {
        assertThatThrownBy(() -> new SettledObservation(-1, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative imputed count is rejected at construction")
    void negative_imputed_count_rejected() {
        assertThatThrownBy(() -> new SettledObservation(10, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
