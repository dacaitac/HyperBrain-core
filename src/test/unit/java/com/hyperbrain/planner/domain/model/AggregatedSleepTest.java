package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregate keeps two truths apart that a single interval would average into a lie: the summed
 * durations the score is computed from, and the real hours the row is stamped with. These tests pin
 * that separation and the one rule that decides which session lends its hours.
 */
@DisplayName("AggregatedSleep — the day's sleep summed, and the hours it is stamped with")
class AggregatedSleepTest {

    private static final OffsetDateTime NIGHT_START =
        OffsetDateTime.of(2026, 8, 6, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NIGHT_END = NIGHT_START.plusHours(7);

    @Test
    @DisplayName("a single-session day is its own totals, its own hours and its only session")
    void a_single_session_is_the_whole_aggregate() {
        // The shape the canonical telemetry pipeline produces: one already-aggregated session, whose
        // own window IS the truth — no fictitious duration carrier is invented for it.
        SleepStageSample sample = new SleepStageSample(
            NIGHT_START, NIGHT_END, 25200, 14400, 3600, 5400, 0, 1800);

        AggregatedSleep sleep = AggregatedSleep.ofSingleSession(sample);

        assertThat(sleep.totals()).usingRecursiveComparison().isEqualTo(sample);
        assertThat(sleep.mainSession()).usingRecursiveComparison()
            .isEqualTo(new SleepSession(NIGHT_START, NIGHT_END, sample.totalSleepSeconds()));
        assertThat(sleep.sessions()).containsExactly(sleep.mainSession());
    }

    @Test
    @DisplayName("the longest session lends the row its hours — the nap never becomes the wake time")
    void the_main_session_is_the_longest() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession nap = new SleepSession(
            NIGHT_END.plusHours(4), NIGHT_END.plusHours(7), 2 * 3600);

        assertThat(AggregatedSleep.mainOf(List.of(night, nap))).isEqualTo(night);
        // Order of arrival does not decide it; the time asleep does.
        assertThat(AggregatedSleep.mainOf(List.of(nap, night))).isEqualTo(night);
    }

    @Test
    @DisplayName("on an exact tie the earlier session keeps the hours, so a night beats a later nap")
    void a_tie_resolves_to_the_earlier_session() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 3 * 3600);
        SleepSession nap = new SleepSession(
            NIGHT_END.plusHours(4), NIGHT_END.plusHours(8), 3 * 3600);

        assertThat(AggregatedSleep.mainOf(List.of(night, nap))).isEqualTo(night);
    }

    @Test
    @DisplayName("there is no main session of nothing")
    void an_empty_period_has_no_main_session() {
        List<SleepSession> none = List.of();

        assertThatThrownBy(() -> AggregatedSleep.mainOf(none))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no session");
    }

    @Test
    @DisplayName("an aggregate without totals, hours or a single session is refused")
    void the_aggregate_requires_all_three_parts() {
        SleepStageSample totals = new SleepStageSample(
            NIGHT_START, NIGHT_END, 25200, 14400, 3600, 5400, 0, 1800);
        SleepSession session = new SleepSession(NIGHT_START, NIGHT_END, 23400);
        List<SleepSession> one = List.of(session);
        List<SleepSession> none = List.of();

        assertThatThrownBy(() -> new AggregatedSleep(null, session, one))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totals and a main session");
        assertThatThrownBy(() -> new AggregatedSleep(totals, null, one))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totals and a main session");
        assertThatThrownBy(() -> new AggregatedSleep(totals, session, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one session");
        assertThatThrownBy(() -> new AggregatedSleep(totals, session, none))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one session");
        assertThatThrownBy(() -> AggregatedSleep.ofSingleSession(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("the sessions are copied in and immutable out — the day's sleep cannot be edited later")
    void the_sessions_are_defensively_copied() {
        SleepStageSample totals = new SleepStageSample(
            NIGHT_START, NIGHT_END, 25200, 14400, 3600, 5400, 0, 1800);
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 23400);
        List<SleepSession> mutable = new ArrayList<>(List.of(night));

        AggregatedSleep sleep = new AggregatedSleep(totals, night, mutable);
        mutable.add(new SleepSession(NIGHT_END.plusHours(4), NIGHT_END.plusHours(5), 3600));

        assertThat(sleep.sessions()).containsExactly(night);
        assertThatThrownBy(() -> sleep.sessions().add(night))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
