package com.hyperbrain.prioritizer.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AlignmentWeights (graded alignment constants)")
class AlignmentWeightsTest {

    @Test
    @DisplayName("DEFAULT carries the sanctioned bands and decay (Comité 2026-07-09)")
    void default_bands_and_decay() {
        AlignmentWeights w = AlignmentWeights.DEFAULT;

        assertThat(w.bandWeight(CycleType.MCI)).isEqualTo(1.0);
        assertThat(w.bandWeight(CycleType.GOAL)).isEqualTo(0.5);
        assertThat(w.bandWeight(CycleType.OBJECTIVE)).isEqualTo(0.4);
        assertThat(w.bandWeight(CycleType.PROJECT)).isEqualTo(0.3);
        assertThat(w.bandWeight(CycleType.ROUTINE)).isEqualTo(0.15);
    }

    @Test
    @DisplayName("PHASE maps to the PROJECT band (0.3) by default — reported mapping")
    void phase_defaults_to_project_band() {
        assertThat(AlignmentWeights.DEFAULT.bandWeight(CycleType.PHASE)).isEqualTo(0.3);
    }

    @Test
    @DisplayName("decay is δ(0)=δ(1)=1.0, δ(2)=0.9, δ(≥3)=0.8")
    void decay_schedule() {
        AlignmentWeights w = AlignmentWeights.DEFAULT;

        assertThat(w.decay(0)).isEqualTo(1.0);
        assertThat(w.decay(1)).isEqualTo(1.0);
        assertThat(w.decay(2)).isEqualTo(0.9);
        assertThat(w.decay(3)).isEqualTo(0.8);
        assertThat(w.decay(9)).isEqualTo(0.8);
    }

    @Test
    @DisplayName("every CycleType must be mapped; a missing type is rejected")
    void missing_band_is_rejected() {
        Map<CycleType, Double> incomplete = new EnumMap<>(CycleType.class);
        incomplete.put(CycleType.MCI, 1.0);

        assertThatThrownBy(() -> new AlignmentWeights(incomplete, 1.0, 0.9, 0.8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("band weights and decay must lie within [0, 1]")
    void out_of_range_values_are_rejected() {
        assertThatThrownBy(() -> new AlignmentWeights(fullBands(), 1.5, 0.9, 0.8))
            .isInstanceOf(IllegalArgumentException.class);

        Map<CycleType, Double> bands = fullBands();
        bands.put(CycleType.MCI, 2.0);
        assertThatThrownBy(() -> new AlignmentWeights(bands, 1.0, 0.9, 0.8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null band map is rejected")
    void null_bands_rejected() {
        assertThatThrownBy(() -> new AlignmentWeights(null, 1.0, 0.9, 0.8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<CycleType, Double> fullBands() {
        Map<CycleType, Double> bands = new HashMap<>();
        for (CycleType type : CycleType.values()) {
            bands.put(type, 0.5);
        }
        return bands;
    }
}
