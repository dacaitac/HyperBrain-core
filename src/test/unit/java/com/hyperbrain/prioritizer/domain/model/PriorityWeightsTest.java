package com.hyperbrain.prioritizer.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PriorityWeights")
class PriorityWeightsTest {

    @Test
    @DisplayName("the sanctioned default split is 40/30/10/20")
    void default_split() {
        assertThat(PriorityWeights.DEFAULT.wImpact()).isEqualTo(0.4);
        assertThat(PriorityWeights.DEFAULT.wUrgency()).isEqualTo(0.3);
        assertThat(PriorityWeights.DEFAULT.wEffort()).isEqualTo(0.1);
        assertThat(PriorityWeights.DEFAULT.wAlignment()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("rejects a weight outside [0, 1]")
    void rejects_out_of_range_weight() {
        assertThatThrownBy(() -> new PriorityWeights(1.5, 0.3, 0.1, 0.2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wImpact");

        assertThatThrownBy(() -> new PriorityWeights(0.4, -0.1, 0.1, 0.2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wUrgency");
    }
}
