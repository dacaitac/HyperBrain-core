package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.PlanningWindow;
import com.hyperbrain.planner.domain.model.SleepWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlanningWindowResolver (#6a, concrete-day window + both modes)")
class PlanningWindowResolverTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);

    private final PlanningWindowResolver resolver = new PlanningWindowResolver();

    @Test
    @DisplayName("full-day mode: lower bound is the wake edge, frontier spans wake..bedtime")
    void full_day_lower_bound_is_wake() {
        SleepWindow sleep = SleepWindow.observed(LocalTimeOfDay.of(6, 30), LocalTimeOfDay.of(23, 0));
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC);

        PlanningWindow window = resolver.resolve(sleep, DAY, UTC, now, false);

        assertThat(window.frontierStart()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 6, 30, 0, 0, ZoneOffset.UTC));
        assertThat(window.frontierEnd()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC));
        assertThat(window.lowerBound()).isEqualTo(window.frontierStart());
    }

    @Test
    @DisplayName("replan-from-now mode: lower bound is clamped to now within the frontier")
    void replan_lower_bound_is_now() {
        SleepWindow sleep = SleepWindow.observed(LocalTimeOfDay.of(6, 30), LocalTimeOfDay.of(23, 0));
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 10, 14, 0, 0, 0, ZoneOffset.UTC);

        PlanningWindow window = resolver.resolve(sleep, DAY, UTC, now, true);

        assertThat(window.lowerBound()).isEqualTo(now);
    }

    @Test
    @DisplayName("replan before wake: lower bound clamps up to the wake edge (never before the frontier)")
    void replan_before_wake_clamps_to_wake() {
        SleepWindow sleep = SleepWindow.observed(LocalTimeOfDay.of(6, 30), LocalTimeOfDay.of(23, 0));
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 10, 5, 0, 0, 0, ZoneOffset.UTC);

        PlanningWindow window = resolver.resolve(sleep, DAY, UTC, now, true);

        assertThat(window.lowerBound()).isEqualTo(window.frontierStart());
    }

    @Test
    @DisplayName("replan late in the day: lower bound clamps down to the bedtime edge")
    void replan_late_clamps_to_bedtime() {
        SleepWindow sleep = SleepWindow.observed(LocalTimeOfDay.of(6, 30), LocalTimeOfDay.of(23, 0));
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);

        PlanningWindow window = resolver.resolve(sleep, DAY, UTC, now, true);

        assertThat(window.lowerBound()).isEqualTo(window.frontierEnd());
    }

    @Test
    @DisplayName("bedtime past midnight: the frontier end lifts to the next calendar day")
    void bedtime_after_midnight_crosses_day() {
        SleepWindow sleep = SleepWindow.observed(LocalTimeOfDay.of(7, 0), LocalTimeOfDay.of(1, 0));
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC);

        PlanningWindow window = resolver.resolve(sleep, DAY, UTC, now, false);

        assertThat(window.frontierStart()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC));
        assertThat(window.frontierEnd()).isEqualTo(OffsetDateTime.of(2026, 7, 11, 1, 0, 0, 0, ZoneOffset.UTC));
    }
}
