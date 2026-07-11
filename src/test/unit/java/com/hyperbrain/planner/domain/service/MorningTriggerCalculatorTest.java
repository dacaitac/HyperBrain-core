package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MorningTriggerState;
import com.hyperbrain.planner.domain.model.SleepWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MorningTriggerCalculator (HU-01b — wake + offset, ±15 min hysteresis)")
class MorningTriggerCalculatorTest {

    private static final int LEAD_OFFSET = 10;
    private static final int HYSTERESIS = 15;

    private final MorningTriggerCalculator calculator =
        new MorningTriggerCalculator(LEAD_OFFSET, HYSTERESIS);

    @Test
    @DisplayName("cold start: with no anchor the trigger is wake + lead offset, unclamped")
    void cold_start_uses_raw_wake_plus_offset() {
        // Given a 06:30 wake and no previous trigger
        SleepWindow window = SleepWindow.observed(LocalTimeOfDay.of(6, 30), LocalTimeOfDay.of(23, 0));

        // When
        LocalTimeOfDay trigger = calculator.resolveTrigger(window, MorningTriggerState.EMPTY);

        // Then 06:30 + 10 = 06:40
        assertThat(trigger.minutesOfDay()).isEqualTo(LocalTimeOfDay.of(6, 40).minutesOfDay());
    }

    @Test
    @DisplayName("within margin: a small day-to-day move is applied verbatim")
    void small_move_is_not_clamped() {
        // Given yesterday's trigger 06:40 and today's raw wake+offset 06:50 (a 10-min move ≤ 15)
        SleepWindow window = SleepWindow.observed(LocalTimeOfDay.of(6, 40), LocalTimeOfDay.of(23, 0));
        MorningTriggerState state = anchoredAt(LocalTimeOfDay.of(6, 40));

        // When
        LocalTimeOfDay trigger = calculator.resolveTrigger(window, state);

        // Then the full 06:50 stands (10 ≤ 15)
        assertThat(trigger.minutesOfDay()).isEqualTo(LocalTimeOfDay.of(6, 50).minutesOfDay());
    }

    @Test
    @DisplayName("clamp forward: a large later shift moves at most +15 min vs. yesterday")
    void large_forward_move_is_clamped_to_plus_margin() {
        // Given yesterday's trigger 06:40 and today's raw wake+offset 08:00 (an +80-min raw move)
        SleepWindow window = SleepWindow.observed(LocalTimeOfDay.of(7, 50), LocalTimeOfDay.of(23, 0));
        MorningTriggerState state = anchoredAt(LocalTimeOfDay.of(6, 40));

        // When
        LocalTimeOfDay trigger = calculator.resolveTrigger(window, state);

        // Then it advances only to 06:40 + 15 = 06:55
        assertThat(trigger.minutesOfDay()).isEqualTo(LocalTimeOfDay.of(6, 55).minutesOfDay());
    }

    @Test
    @DisplayName("clamp backward: a large earlier shift moves at most −15 min vs. yesterday")
    void large_backward_move_is_clamped_to_minus_margin() {
        // Given yesterday's trigger 07:00 and today's raw wake+offset 05:40 (a −80-min raw move)
        SleepWindow window = SleepWindow.observed(LocalTimeOfDay.of(5, 30), LocalTimeOfDay.of(23, 0));
        MorningTriggerState state = anchoredAt(LocalTimeOfDay.of(7, 0));

        // When
        LocalTimeOfDay trigger = calculator.resolveTrigger(window, state);

        // Then it retreats only to 07:00 − 15 = 06:45
        assertThat(trigger.minutesOfDay()).isEqualTo(LocalTimeOfDay.of(6, 45).minutesOfDay());
    }

    @Test
    @DisplayName("midnight seam: the clamp measures the shortest arc, never the long way around")
    void clamp_takes_shortest_arc_across_midnight() {
        // Given yesterday's trigger 00:05 and a raw wake+offset at 23:30 (a −35-min arc, clamped to −15)
        SleepWindow window = SleepWindow.observed(LocalTimeOfDay.of(23, 20), LocalTimeOfDay.of(6, 0));
        MorningTriggerState state = anchoredAt(LocalTimeOfDay.of(0, 5));

        // When
        LocalTimeOfDay trigger = calculator.resolveTrigger(window, state);

        // Then it moves back only 15 min from 00:05 → 23:50, wrapping the seam
        assertThat(trigger.minutesOfDay()).isEqualTo(LocalTimeOfDay.of(23, 50).minutesOfDay());
    }

    private static MorningTriggerState anchoredAt(LocalTimeOfDay previousTrigger) {
        return new MorningTriggerState(previousTrigger, LocalDate.of(2026, 7, 9));
    }
}
