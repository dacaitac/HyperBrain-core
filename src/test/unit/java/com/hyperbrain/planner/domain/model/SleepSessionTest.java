package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A session is the one part of the sleep model that is safe to read as wall-clock truth — it is shown
 * to the model as {@code slept_windows} and stored in {@code tel_sleep_record.stages} — so what it
 * refuses to be built from is what keeps a nonsense hour off both.
 */
@DisplayName("SleepSession — a stretch of sleep on the clock, and what it refuses to be")
class SleepSessionTest {

    private static final OffsetDateTime BEDTIME =
        OffsetDateTime.of(2026, 8, 6, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime WAKE = BEDTIME.plusHours(7);

    @Test
    @DisplayName("its span is time in bed, whatever share of it was actually asleep")
    void the_window_span_is_time_in_bed() {
        SleepSession session = new SleepSession(BEDTIME, WAKE, 6 * 3600);

        assertThat(session.windowSeconds()).isEqualTo(7 * 3600);
        assertThat(session.asleepSeconds()).isEqualTo(6 * 3600);
    }

    @Test
    @DisplayName("a window with no time in it is refused: an instant is not a session")
    void a_degenerate_window_is_refused() {
        assertThatThrownBy(() -> new SleepSession(BEDTIME, BEDTIME, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("end must be after start");
        assertThatThrownBy(() -> new SleepSession(WAKE, BEDTIME, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("end must be after start");
    }

    @Test
    @DisplayName("an absent instant is refused rather than defaulted to now")
    void a_missing_instant_is_refused() {
        assertThatThrownBy(() -> new SleepSession(null, WAKE, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("start and end");
        assertThatThrownBy(() -> new SleepSession(BEDTIME, null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("start and end");
    }

    @Test
    @DisplayName("negative sleep is refused — it would subtract from the day's total")
    void negative_asleep_seconds_are_refused() {
        assertThatThrownBy(() -> new SleepSession(BEDTIME, WAKE, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    @DisplayName("a session with no sleep in it is allowed: time in bed awake is still a fact")
    void zero_asleep_seconds_are_allowed() {
        assertThat(new SleepSession(BEDTIME, WAKE, 0).asleepSeconds()).isZero();
    }
}
