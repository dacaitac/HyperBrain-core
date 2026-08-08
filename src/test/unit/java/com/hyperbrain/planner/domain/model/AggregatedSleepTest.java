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
 * that separation and the rule that resolves the night out of the day's sessions.
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
        assertThat(sleep.night()).usingRecursiveComparison()
            .isEqualTo(new SleepSession(NIGHT_START, NIGHT_END, sample.totalSleepSeconds()));
        assertThat(sleep.sessions()).containsExactly(sleep.night());
        assertThat(sleep.naps()).isEmpty();
    }

    @Test
    @DisplayName("an unbroken night lends the row its hours — the nap never becomes the wake time")
    void the_night_is_the_longest_run_of_sleep() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession nap = new SleepSession(
            NIGHT_END.plusHours(6), NIGHT_END.plusHours(8), 2 * 3600);

        assertThat(AggregatedSleep.nightOf(List.of(night, nap))).isEqualTo(night);
    }

    @Test
    @DisplayName("a broken night is ONE night, spanning from the first fragment to the last")
    void a_broken_night_is_still_one_night() {
        // Daniel, 2026-08-08: «anoche dormí en dos fases porque me desperté en la madrugada». Reading
        // the hours off the longest fragment declared he got up at 03:00 — and that is what the sleep
        // frontier then learned as his wake time.
        SleepSession firstHalf = new SleepSession(NIGHT_START, NIGHT_START.plusHours(4), 4 * 3600);
        SleepSession secondHalf = new SleepSession(
            NIGHT_START.plusHours(7).plusMinutes(30), NIGHT_START.plusHours(9), 90 * 60);

        SleepSession night = AggregatedSleep.nightOf(List.of(firstHalf, secondHalf));

        // The span is the whole night on the clock, hole included...
        assertThat(night.start()).isEqualTo(NIGHT_START);
        assertThat(night.end()).isEqualTo(NIGHT_START.plusHours(9));
        // ...while the sleep inside it is only what was actually slept: the hole is not sleep.
        assertThat(night.asleepSeconds()).isEqualTo(4 * 3600 + 90 * 60);
        assertThat(night.windowSeconds()).isGreaterThan(night.asleepSeconds());
    }

    @Test
    @DisplayName("the night grows backwards too: an early fragment before the longest one joins it")
    void the_night_grows_in_both_directions() {
        SleepSession firstHalf = new SleepSession(NIGHT_START, NIGHT_START.plusHours(1), 3600);
        SleepSession longest = new SleepSession(
            NIGHT_START.plusHours(3), NIGHT_START.plusHours(9), 6 * 3600);

        SleepSession night = AggregatedSleep.nightOf(List.of(firstHalf, longest));

        assertThat(night.start()).isEqualTo(NIGHT_START);
        assertThat(night.end()).isEqualTo(NIGHT_START.plusHours(9));
    }

    @Test
    @DisplayName("under five hours apart is the same night; five hours sharp is a separate sleep")
    void the_same_night_threshold_is_five_hours_exclusive() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 7 * 3600);
        SleepSession justInside = new SleepSession(
            NIGHT_END.plusMinutes(299), NIGHT_END.plusMinutes(359), 3600);   // 4 h 59 later
        SleepSession onTheThreshold = new SleepSession(
            NIGHT_END.plusHours(5), NIGHT_END.plusHours(6), 3600);           // 5 h sharp

        assertThat(AggregatedSleep.nightOf(List.of(night, justInside)).end())
            .isEqualTo(justInside.end());
        assertThat(AggregatedSleep.nightOf(List.of(night, onTheThreshold)).end())
            .isEqualTo(NIGHT_END);
    }

    @Test
    @DisplayName("a nap is a session outside the night, whatever its length")
    void naps_are_the_sessions_outside_the_night() {
        SleepSession firstHalf = new SleepSession(NIGHT_START, NIGHT_START.plusHours(4), 4 * 3600);
        SleepSession secondHalf = new SleepSession(
            NIGHT_START.plusHours(7).plusMinutes(30), NIGHT_START.plusHours(9), 90 * 60);
        SleepSession afternoonNap = new SleepSession(
            NIGHT_START.plusHours(15), NIGHT_START.plusHours(16), 3600);
        List<SleepSession> sessions = List.of(firstHalf, secondHalf, afternoonNap);
        SleepStageSample totals = new SleepStageSample(
            NIGHT_START, NIGHT_START.plusSeconds(4 * 3600 + 90 * 60 + 3600), 0, 23400, 0, 0, 0, 0);

        AggregatedSleep sleep = new AggregatedSleep(
            totals, AggregatedSleep.nightOf(sessions), sessions);

        // The 90-minute fragment at half past six is night, not a nap at half past six.
        assertThat(sleep.naps()).containsExactly(afternoonNap);
    }

    @Test
    @DisplayName("on an exact tie the earlier session seeds the night, so a night beats a later nap")
    void a_tie_resolves_to_the_earlier_session() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 3 * 3600);
        SleepSession nap = new SleepSession(
            NIGHT_END.plusHours(6), NIGHT_END.plusHours(10), 3 * 3600);

        assertThat(AggregatedSleep.nightOf(List.of(night, nap))).isEqualTo(night);
    }

    @Test
    @DisplayName("there is no night of nothing")
    void an_empty_period_has_no_night() {
        List<SleepSession> none = List.of();

        assertThatThrownBy(() -> AggregatedSleep.nightOf(none))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no session to resolve a night from");
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
            .hasMessageContaining("totals and a night");
        assertThatThrownBy(() -> new AggregatedSleep(totals, null, one))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totals and a night");
        assertThatThrownBy(() -> new AggregatedSleep(totals, session, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one session");
        assertThatThrownBy(() -> new AggregatedSleep(totals, session, none))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one session");
        assertThatThrownBy(() -> AggregatedSleep.ofSingleSession(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
        assertThatThrownBy(() -> new AggregatedSleep(totals, session, one, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlap seconds must be non-negative");
    }

    @Test
    @DisplayName("an aggregate built without an overlap measurement reports none, never a guess")
    void an_unmeasured_aggregate_reports_no_overlap() {
        // The three-argument form is what an already-aggregated payload produces: nothing was contested
        // because nothing could be. Defaulting to anything other than zero would fabricate evidence
        // about a reading nobody looked at.
        SleepStageSample totals = new SleepStageSample(
            NIGHT_START, NIGHT_END, 25200, 14400, 3600, 5400, 0, 1800);
        SleepSession session = new SleepSession(NIGHT_START, NIGHT_END, 23400);

        assertThat(new AggregatedSleep(totals, session, List.of(session)).overlapSeconds()).isZero();
        assertThat(AggregatedSleep.ofSingleSession(totals).overlapSeconds()).isZero();
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
