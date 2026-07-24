package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SleepStageSample (value-object invariants)")
class SleepStageSampleTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-07-10T22:00:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-07-11T06:00:00Z");

    @Test
    @DisplayName("total sleep time sums core, deep, REM and unspecified — never inBed or awake")
    void total_sleep_time_sums_asleep_stages() {
        SleepStageSample sample = new SleepStageSample(START, END, 900, 17280, 5184, 6336, 120, 600);

        assertThat(sample.totalSleepSeconds()).isEqualTo(17280 + 5184 + 6336 + 120);
    }

    @Test
    @DisplayName("a stage breakdown is present when any of core/deep/REM is non-zero")
    void has_phase_breakdown_when_any_stage_present() {
        assertThat(new SleepStageSample(START, END, 0, 0, 5184, 0, 0, 0).hasPhaseBreakdown()).isTrue();
        assertThat(new SleepStageSample(START, END, 0, 0, 0, 0, 28800, 0).hasPhaseBreakdown()).isFalse();
    }

    @Test
    @DisplayName("end must be after start")
    void end_must_be_after_start() {
        assertThatThrownBy(() -> new SleepStageSample(END, START, 0, 100, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("end must be after start");
    }

    @Test
    @DisplayName("negative durations are rejected")
    void negative_durations_rejected() {
        assertThatThrownBy(() -> new SleepStageSample(START, END, 0, -1, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }
}
