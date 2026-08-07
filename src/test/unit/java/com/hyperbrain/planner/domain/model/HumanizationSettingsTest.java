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
    @DisplayName("the sanctioned defaults keep the three meal anchors and the batching band")
    void defaults_keep_the_meals_and_the_band() {
        // Then
        assertThat(HumanizationSettings.DEFAULT.mealWindows())
            .extracting(MealWindow::label).containsExactly("breakfast", "lunch", "dinner");
        assertThat(HumanizationSettings.DEFAULT.batchBandWidth()).isEqualTo(0.10);
    }

    @Test
    @DisplayName("every default meal may float inside a plausible band wider than its own hour")
    void every_default_meal_carries_a_plausible_band() {
        // Then: the band strictly encloses the anchor, which is what lets a meal slide without ever
        // landing at an hour nobody eats at (breakfast in the afternoon was a real day).
        assertThat(HumanizationSettings.DEFAULT.mealWindows()).allSatisfy(meal -> {
            assertThat(meal.bandStart()).isBefore(meal.start());
            assertThat(meal.bandEnd()).isAfter(meal.end());
        });
        assertThat(HumanizationSettings.DEFAULT.mealWindows())
            .filteredOn(meal -> meal.label().equals("breakfast"))
            .singleElement()
            .satisfies(breakfast -> {
                assertThat(breakfast.bandStart()).isEqualTo(LocalTime.of(5, 30));
                assertThat(breakfast.bandEnd()).isEqualTo(LocalTime.of(10, 0));
            });
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
