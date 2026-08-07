package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HumanizationSettings — what survives the retirement of the capacity discounts (ADR-040 D1)")
class HumanizationSettingsTest {

    @Test
    @DisplayName("the sanctioned defaults keep the two meal anchors and the batching band")
    void defaults_keep_the_meals_and_the_band() {
        // Then
        assertThat(HumanizationSettings.DEFAULT.mealWindows())
            .extracting(MealWindow::label).containsExactly("lunch", "dinner");
        assertThat(HumanizationSettings.DEFAULT.batchBandWidth()).isEqualTo(0.10);
    }

    @Test
    @DisplayName("the no-op instance protects nothing and batches nothing — the raw floor")
    void the_no_op_instance_is_bare() {
        // Then
        assertThat(HumanizationSettings.NO_OP.mealWindows()).isEmpty();
        assertThat(HumanizationSettings.NO_OP.batchBandWidth()).isZero();
    }

    @Test
    @DisplayName("meal anchors are defensively copied: a caller cannot mutate the walls afterwards")
    void meal_windows_are_copied() {
        // Given
        List<MealWindow> mutable = new java.util.ArrayList<>(
            List.of(new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30))));
        HumanizationSettings settings = new HumanizationSettings(mutable, 0.1);

        // When
        mutable.clear();

        // Then
        assertThat(settings.mealWindows()).hasSize(1);
    }

    @Test
    @DisplayName("a batching band outside [0, 1] is rejected — it is a score tolerance, not a count")
    void an_out_of_range_band_is_rejected() {
        assertThatThrownBy(() -> new HumanizationSettings(List.of(), 1.5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanizationSettings(List.of(), -0.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null meal list means no anchors, never a null pointer downstream")
    void a_null_meal_list_degrades_to_empty() {
        assertThat(new HumanizationSettings(null, 0.1).mealWindows()).isEmpty();
    }
}
