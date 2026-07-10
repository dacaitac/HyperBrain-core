package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.model.SleepWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SleepFrontierCalculator (#6a, circular median frontier)")
class SleepFrontierCalculatorTest {

    private static final SleepWindow FALLBACK =
        SleepWindow.fallback(LocalTimeOfDay.of(7, 0), LocalTimeOfDay.of(22, 30));

    private final SleepFrontierCalculator calculator =
        new SleepFrontierCalculator(PlannerConstraints.DEFAULT);

    @Test
    @DisplayName("fewer than N usable nights: both edges fall back to the settings window (not observed)")
    void below_minimum_falls_back() {
        // Only 4 nights, N minimum is 5.
        SleepFrontierInputs inputs = new SleepFrontierInputs(
            times(4, LocalTimeOfDay.of(6, 30)), times(4, LocalTimeOfDay.of(23, 0)), FALLBACK);

        SleepWindow window = calculator.computeWindow(inputs);

        assertThat(window.observed()).isFalse();
        assertThat(window.wakeEstimate()).isEqualTo(LocalTimeOfDay.of(7, 0));
        assertThat(window.bedtimeEstimate()).isEqualTo(LocalTimeOfDay.of(22, 30));
    }

    @Test
    @DisplayName("enough nights: the observed circular median wins over the fallback")
    void enough_nights_uses_observed_median() {
        SleepFrontierInputs inputs = new SleepFrontierInputs(
            List.of(LocalTimeOfDay.of(6, 0), LocalTimeOfDay.of(6, 30), LocalTimeOfDay.of(7, 0),
                LocalTimeOfDay.of(6, 45), LocalTimeOfDay.of(6, 15)),
            List.of(LocalTimeOfDay.of(23, 0), LocalTimeOfDay.of(22, 30), LocalTimeOfDay.of(23, 30),
                LocalTimeOfDay.of(23, 15), LocalTimeOfDay.of(22, 45)),
            FALLBACK);

        SleepWindow window = calculator.computeWindow(inputs);

        assertThat(window.observed()).isTrue();
        // Wake samples sorted: 6:00 6:15 6:30 6:45 7:00 -> median 6:30
        assertThat(window.wakeEstimate()).isEqualTo(LocalTimeOfDay.of(6, 30));
        // Bedtime sorted: 22:30 22:45 23:00 23:15 23:30 -> median 23:00
        assertThat(window.bedtimeEstimate()).isEqualTo(LocalTimeOfDay.of(23, 0));
    }

    @Test
    @DisplayName("circular median across the midnight seam: 23:50 and 00:10 average to ~00:00, not noon")
    void circular_median_wraps_midnight() {
        // Bedtimes straddling midnight; the linear median would be ~12:00.
        SleepFrontierInputs inputs = new SleepFrontierInputs(
            times(5, LocalTimeOfDay.of(7, 0)),
            List.of(LocalTimeOfDay.of(23, 50), LocalTimeOfDay.of(0, 10), LocalTimeOfDay.of(0, 0),
                LocalTimeOfDay.of(23, 55), LocalTimeOfDay.of(0, 5)),
            FALLBACK);

        SleepWindow window = calculator.computeWindow(inputs);

        // The circular median lands on ~00:00, never noon.
        int minutes = window.bedtimeEstimate().minutesOfDay();
        assertThat(minutes == 0 || minutes >= 1435 || minutes <= 5).isTrue();
    }

    @Test
    @DisplayName("even sample count: the circular median averages the two middle values")
    void even_count_averages_middle() {
        SleepFrontierInputs inputs = new SleepFrontierInputs(
            List.of(LocalTimeOfDay.of(6, 0), LocalTimeOfDay.of(6, 20), LocalTimeOfDay.of(6, 40),
                LocalTimeOfDay.of(7, 0), LocalTimeOfDay.of(7, 20), LocalTimeOfDay.of(7, 40)),
            times(6, LocalTimeOfDay.of(23, 0)),
            FALLBACK);

        SleepWindow window = calculator.computeWindow(inputs);

        // Middle two of six sorted wake samples: 6:40 and 7:00 -> 6:50.
        assertThat(window.wakeEstimate()).isEqualTo(LocalTimeOfDay.of(6, 50));
    }

    @Test
    @DisplayName("one edge observed, the other short: only the short edge falls back, window not observed")
    void mixed_edges_are_not_observed() {
        SleepFrontierInputs inputs = new SleepFrontierInputs(
            times(5, LocalTimeOfDay.of(6, 30)),   // enough wake samples
            times(2, LocalTimeOfDay.of(23, 0)),   // too few bedtime samples
            FALLBACK);

        SleepWindow window = calculator.computeWindow(inputs);

        assertThat(window.observed()).isFalse();
        assertThat(window.wakeEstimate()).isEqualTo(LocalTimeOfDay.of(6, 30));
        assertThat(window.bedtimeEstimate()).isEqualTo(FALLBACK.bedtimeEstimate());
    }

    private static List<LocalTimeOfDay> times(int count, LocalTimeOfDay value) {
        return IntStream.range(0, count).mapToObj(i -> value).toList();
    }
}
