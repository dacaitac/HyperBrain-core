package com.hyperbrain.planner.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.ParsedSleepDay;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SleepSampleSessionParser — raw HealthKit dump → the sleep day (night + naps), summed")
class SleepSampleSessionParserTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    // Narrow no-break space (U+202F): exactly what Apple inserts before AM/PM in the Shortcut dump.
    private static final char NNBSP = '\u202f';

    private final SleepSampleSessionParser parser = new SleepSampleSessionParser();

    @Test
    @DisplayName("distils the real Shortcut fixture: the period's night, per-stage interval union")
    void parses_real_shortcut_fixture() throws Exception {
        DeviceSleepSamples dump = loadFixture("/fixtures/shortcut_sleep_sample.json");

        ParsedSleepDay day = parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-23T23:00:00Z"));

        // The fixture holds two nights and captures at 23 Jul 22:12; only the night that reaches into
        // the relevant period (22 Jul 23:41 → 23 Jul 07:51) is summed — the one two days back is not.
        SleepStageSample sample = day.sleep().totals();
        assertThat(day.sleep().sessions()).hasSize(1);
        assertThat(day.sleep().mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-22T23:41:00Z"));
        assertThat(day.sleep().mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-23T07:51:00Z"));
        assertThat(sample.start()).isEqualTo(OffsetDateTime.parse("2026-07-22T23:41:00Z"));
        assertThat(sample.end()).isEqualTo(OffsetDateTime.parse("2026-07-23T07:51:00Z"));

        // TST is the union of every asleep interval on one timeline (~7.45 h), NOT the 40 200 s sum of
        // per-stage unions, which would falsely exceed time in bed.
        long tst = sample.totalSleepSeconds();
        assertThat(tst).isCloseTo(26820L, within(60L));
        // Overlap-resolved per-stage seconds (Deep > REM > Core) sum exactly to TST — no double-count.
        assertThat(sample.deepSeconds()).isEqualTo(9420);
        assertThat(sample.remSeconds()).isEqualTo(7920);
        assertThat(sample.coreSeconds()).isEqualTo(9480);
        assertThat(sample.unspecifiedSeconds()).isZero();
        assertThat(sample.coreSeconds() + sample.deepSeconds() + sample.remSeconds()
            + sample.unspecifiedSeconds()).isEqualTo(tst);

        // Awake (WASO) and In Bed (TIB ≈ 8.03 h) are plain unions; the invariant TST ≤ TIB now holds.
        assertThat(sample.awakeSeconds()).isEqualTo(3180);
        assertThat(sample.inBedSeconds()).isEqualTo(28920);
        long windowSeconds = Duration.between(sample.start(), sample.end()).toSeconds();
        assertThat(tst).isLessThanOrEqualTo(sample.inBedSeconds()).isLessThanOrEqualTo(windowSeconds);

        // The capture date ("23/07/2026 at 10:12 PM", with U+202F) is the collection instant.
        assertThat(day.collectedAt()).isEqualTo(OffsetDateTime.parse("2026-07-23T22:12:00Z"));
    }

    @Test
    @DisplayName("sums the night and the afternoon nap instead of letting the nap displace the night")
    void sums_night_and_nap() {
        // The production defect: a 09:20–13:56 nap became the whole day and scored 13.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 1:00 AM"),
            sample("Awake", "10/07/2026 at 1:00 AM", "10/07/2026 at 1:20 AM"),
            sample("Core", "10/07/2026 at 1:20 AM", "10/07/2026 at 5:00 AM"),
            sample("Core", "10/07/2026 at 9:20 AM", "10/07/2026 at 12:00 PM")));

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T14:30:00Z")).sleep();

        // Durations add up across both sessions.
        assertThat(sleep.totals().coreSeconds()).isEqualTo(20400 + 9600);
        assertThat(sleep.totals().awakeSeconds()).isEqualTo(1200);
        assertThat(sleep.totals().totalSleepSeconds()).isEqualTo(30000);

        // The row's hours stay the night's — the nap must never become the learned wake time.
        assertThat(sleep.mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(sleep.mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T05:00:00Z"));

        // Both sessions survive, chronologically, with their own asleep time.
        assertThat(sleep.sessions()).containsExactly(
            new SleepSession(OffsetDateTime.parse("2026-07-09T23:00:00Z"),
                OffsetDateTime.parse("2026-07-10T05:00:00Z"), 20400),
            new SleepSession(OffsetDateTime.parse("2026-07-10T09:20:00Z"),
                OffsetDateTime.parse("2026-07-10T12:00:00Z"), 9600));
    }

    @Test
    @DisplayName("the scorable window carries the summed time in bed, never the awake gap between sessions")
    void scorable_window_spans_summed_time_in_bed() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 5:00 AM"),   // 6 h in bed
            sample("Core", "10/07/2026 at 9:20 AM", "10/07/2026 at 12:00 PM"))); // 2 h 40 in bed

        SleepStageSample totals =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T14:30:00Z")).sleep().totals();

        // 8 h 40 of time in bed, not the 13 h that separate falling asleep from getting up from the nap:
        // the calculator reads TIB as the window, so the gap would sink efficiency for no reason.
        long window = Duration.between(totals.start(), totals.end()).toSeconds();
        assertThat(window).isEqualTo(6 * 3600 + 9600);
        assertThat(totals.totalSleepSeconds()).isLessThanOrEqualTo(window);
    }

    @Test
    @DisplayName("an evening run does not reach back a whole extra day for yesterday's nap")
    void excludes_a_nap_older_than_a_day() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 7:00 PM", "09/07/2026 at 8:00 PM"),    // yesterday evening
            sample("Core", "09/07/2026 at 11:30 PM", "10/07/2026 at 6:00 AM"))); // last night

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();

        // The 24 h bound closes the period at 09 Jul 22:00: the 19:00 nap belongs to yesterday's row.
        assertThat(sleep.sessions()).hasSize(1);
        assertThat(sleep.sessions().getFirst().start())
            .isEqualTo(OffsetDateTime.parse("2026-07-09T23:30:00Z"));
    }

    @Test
    @DisplayName("a morning run does not reach back into the previous day's waking hours")
    void excludes_the_previous_days_afternoon_nap() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 3:00 PM", "09/07/2026 at 4:00 PM"),    // yesterday afternoon
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"))); // last night

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T08:00:00Z")).sleep();

        // The period opens at 09 Jul 18:00 — 24 h back would have swallowed yesterday's afternoon.
        assertThat(sleep.sessions()).hasSize(1);
        assertThat(sleep.sessions().getFirst().start())
            .isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
    }

    @Test
    @DisplayName("the 18:00 that opens the sleep day is the USER's 18:00, not UTC's")
    void the_period_opens_at_eighteen_hundred_in_the_users_zone() {
        // Bogota is UTC-5 all year, so an 18:00 boundary read in UTC lands at 13:00 local and sweeps in
        // five hours of the previous afternoon. The provider's strings carry no zone at all, which is
        // precisely why the zone has to be applied on both sides — to the samples AND to the boundary.
        ZoneId bogota = ZoneId.of("America/Bogota");
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 4:00 PM", "09/07/2026 at 5:00 PM"),   // 16:00-17:00 local
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"))); // last night, local

        AggregatedSleep sleep = parser
            .parse(dump, bogota, OffsetDateTime.parse("2026-07-10T13:00:00Z")) // 08:00 local
            .sleep();

        // The afternoon nap ends at 22:00Z, before the period opens at 09 Jul 18:00 local = 23:00Z. Read
        // the boundary in UTC (18:00Z) and it would have been swallowed into the night's row.
        assertThat(sleep.sessions()).hasSize(1);
        assertThat(sleep.sessions().getFirst().start())
            .isEqualTo(OffsetDateTime.parse("2026-07-10T04:00:00Z"));
    }

    @Test
    @DisplayName("a session that ends exactly as the period opens is out; a minute later it is in")
    void the_period_boundary_is_half_open() {
        // The boundary decides which row a nap lands on, so which side owns the instant itself is not a
        // detail: an inclusive bound would put the same nap on two consecutive days.
        AggregatedSleep flushWithTheBoundary = parser.parse(new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 3:00 PM", "09/07/2026 at 6:00 PM"),    // ends AT 18:00
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"))),
            ZONE, reference()).sleep();

        AggregatedSleep aMinuteInside = parser.parse(new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 3:00 PM", "09/07/2026 at 6:01 PM"),    // ends 18:01
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"))),
            ZONE, reference()).sleep();

        assertThat(flushWithTheBoundary.sessions()).hasSize(1);
        assertThat(aMinuteInside.sessions()).hasSize(2);
    }

    @Test
    @DisplayName("the same nap is kept or dropped by the HOUR the replan ran, not by the day it belongs to")
    void the_verdict_on_an_early_evening_nap_depends_on_when_the_run_happens() {
        // A nap at 19:00 on the 9th sits inside the sleep day the 18:00 rule opens for the 10th. Whether
        // it is counted depends entirely on when the dump is captured, because the 24 h clamp is measured
        // from the anchor rather than from the sleep day: a morning run reaches back to 18:00 and keeps
        // it, an evening run only reaches back 24 h and cuts it off. Same night, same nap, two rows.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 7:00 PM", "09/07/2026 at 8:00 PM"),
            sample("Core", "09/07/2026 at 11:30 PM", "10/07/2026 at 6:00 AM")));

        AggregatedSleep morningRun =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T08:00:00Z")).sleep();
        AggregatedSleep eveningRun =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();

        assertThat(morningRun.sessions()).hasSize(2);
        assertThat(eveningRun.sessions()).hasSize(1);
        // And the sleep the day is scored on differs by the whole nap, on identical raw data.
        assertThat(morningRun.totals().totalSleepSeconds())
            .isEqualTo(eveningRun.totals().totalSleepSeconds() + 3600);
    }

    @Test
    @DisplayName("a nap taken after 18:00 is summed into this run's row AND into tomorrow's")
    void an_evening_nap_is_counted_by_two_consecutive_sleep_days() {
        // The other half of the same asymmetry. A 19:00 nap on the 10th is inside the 24 h an evening
        // run reaches back over, and it is also inside the sleep day the 18:00 rule opens for the 11th —
        // so an evening replan and the next morning's replan both count it, on two different rows.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:30 PM", "10/07/2026 at 6:00 AM"),  // the night
            sample("Core", "10/07/2026 at 7:00 PM", "10/07/2026 at 8:00 PM"))); // the evening nap

        AggregatedSleep tonight =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();
        AggregatedSleep tomorrowMorning =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-11T08:00:00Z")).sleep();

        SleepSession nap = new SleepSession(OffsetDateTime.parse("2026-07-10T19:00:00Z"),
            OffsetDateTime.parse("2026-07-10T20:00:00Z"), 3600);
        assertThat(tonight.sessions()).contains(nap);
        assertThat(tomorrowMorning.sessions()).containsExactly(nap);
    }

    @Test
    @DisplayName("the longest session gives the row its hours; on a tie the earlier one keeps them")
    void the_main_session_is_the_longest_and_the_earliest_on_a_tie() {
        // The row's two instant columns are the chronotype the sleep frontier learns its wake median
        // from, so a nap of exactly the night's length must not carry the day's wake time into the
        // afternoon. Ties resolve to the earlier session, which is the night.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 1:00 AM"),   // 2 h night
            sample("Core", "10/07/2026 at 9:00 AM", "10/07/2026 at 11:00 AM"))); // 2 h nap

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T14:00:00Z")).sleep();

        assertThat(sleep.mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(sleep.mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T01:00:00Z"));
    }

    @Test
    @DisplayName("a zero-length reading is dropped without taking the rest of the dump with it")
    void a_degenerate_session_is_dropped_not_fatal() {
        // A provider sometimes emits an instantaneous sample. On its own it is a session with no time
        // at all, which SleepSession would refuse — so it has to be dropped before it is built, and
        // dropping it must not cost the night.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"),
            sample("Core", "10/07/2026 at 10:00 AM", "10/07/2026 at 10:00 AM")));

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T14:00:00Z")).sleep();

        assertThat(sleep.sessions()).hasSize(1);
        assertThat(sleep.sessions().getFirst().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T06:00:00Z"));
    }

    @Test
    @DisplayName("a stretch of pure wakefulness still becomes a session — with no sleep in it")
    void an_awake_only_cluster_becomes_a_zero_sleep_session() {
        // Pinned rather than endorsed: an Awake cluster far from the night carries no sleep, yet it is
        // summed as time in bed (sinking efficiency) and reaches the model as a slept window of zero
        // minutes. Harmless on Apple Watch data, where Awake only appears inside a session; it is the
        // behaviour a different provider would expose.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 5:00 AM"),
            sample("Awake", "10/07/2026 at 10:00 AM", "10/07/2026 at 10:30 AM")));

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T14:00:00Z")).sleep();

        assertThat(sleep.sessions()).hasSize(2);
        assertThat(sleep.sessions().getLast().asleepSeconds()).isZero();
        assertThat(sleep.totals().totalSleepSeconds()).isEqualTo(6 * 3600);
        // The window carries both spans, so the awake half-hour counts as time in bed.
        assertThat(Duration.between(sleep.totals().start(), sleep.totals().end()).toSeconds())
            .isEqualTo(6 * 3600 + 1800);
    }

    @Test
    @DisplayName("a run without a dump, a zone or an anchor is refused rather than guessed")
    void required_arguments_are_refused_when_absent() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM")));

        assertThatThrownBy(() -> parser.parse(null, ZONE, reference()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dump, zone and reference");
        assertThatThrownBy(() -> parser.parse(dump, null, reference()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(dump, ZONE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a gap that does not separate anything is refused at construction")
    void the_session_gap_must_be_a_positive_duration() {
        // A zero or negative gap would split every sample into its own session and every night would
        // become a string of naps — the failure would show up as a bad score, far from its cause.
        assertThatThrownBy(() -> new SleepSampleSessionParser(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive duration");
        assertThatThrownBy(() -> new SleepSampleSessionParser(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SleepSampleSessionParser(Duration.ofMinutes(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a night that began before the period opens is summed whole, not truncated")
    void keeps_a_session_that_straddles_the_period_boundary() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 9:30 PM", "10/07/2026 at 5:30 AM")));

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();

        // The period opens at 09 Jul 22:00, half an hour after he fell asleep; the session still counts
        // entire, because it is the session that reaches into the period, not the sample.
        assertThat(sleep.mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T21:30:00Z"));
        assertThat(sleep.totals().coreSeconds()).isEqualTo(8 * 3600);
    }

    @Test
    @DisplayName("a dump whose sleep is all older than the period is rejected (no stale row is written)")
    void rejects_a_dump_with_nothing_in_the_period() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "07/07/2026 at 11:00 PM", "08/07/2026 at 6:00 AM")));

        assertThatThrownBy(() -> parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T08:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no sleep session in the relevant period");
    }

    @Test
    @DisplayName("resolves cross-stage overlaps by deepest-wins so per-stage seconds sum to the asleep union")
    void resolves_cross_stage_overlap_by_deepest_wins() {
        // Core spans the whole 2 h; Deep and REM overlap inside it — the deeper stage wins the instant.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 1:00 AM"),   // 23:00–01:00
            sample("Deep", "09/07/2026 at 11:30 PM", "10/07/2026 at 12:00 AM"),  // 30 m carved from Core
            sample("REM", "10/07/2026 at 12:00 AM", "10/07/2026 at 12:15 AM"))); // 15 m carved from Core

        SleepStageSample s = parser.parse(dump, ZONE, reference()).sleep().totals();

        assertThat(s.deepSeconds()).isEqualTo(30 * 60);
        assertThat(s.remSeconds()).isEqualTo(15 * 60);
        assertThat(s.coreSeconds()).isEqualTo(2 * 3600 - 45 * 60);
        assertThat(s.totalSleepSeconds()).isEqualTo(2 * 3600);
        assertThat(s.coreSeconds() + s.deepSeconds() + s.remSeconds()).isEqualTo(s.totalSleepSeconds());
    }

    @Test
    @DisplayName("normalizes the U+202F narrow no-break space before AM/PM when parsing local times")
    void parses_local_times_with_narrow_no_break_space() {
        DeviceSleepSamples dump = new DeviceSleepSamples(
            "10/07/2026 at 7:30" + NNBSP + "AM",
            List.of(new DeviceSleepSamples.Sample(
                "Core", "09/07/2026 at 11:00" + NNBSP + "PM", "10/07/2026 at 6:00" + NNBSP + "AM")));

        ParsedSleepDay day = parser.parse(dump, ZONE, reference());

        assertThat(day.sleep().mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(day.sleep().mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T06:00:00Z"));
        assertThat(day.sleep().totals().coreSeconds()).isEqualTo(7 * 3600);
        assertThat(day.collectedAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T07:30:00Z"));
    }

    @Test
    @DisplayName("unions overlapping same-stage intervals instead of summing them")
    void unions_overlapping_intervals() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 1:00 AM"),   // 2h
            sample("Core", "10/07/2026 at 12:30 AM", "10/07/2026 at 2:00 AM"))); // overlaps 30m

        AggregatedSleep sleep = parser.parse(dump, ZONE, reference()).sleep();

        // Union 23:00 → 02:00 = 3h, not the naive 3.5h sum.
        assertThat(sleep.totals().coreSeconds()).isEqualTo(3 * 3600);
        assertThat(sleep.mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(sleep.mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T02:00:00Z"));
    }

    @Test
    @DisplayName("maps stage labels case-insensitively and ignores unknown stages")
    void maps_stages_and_ignores_unknown() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("deep", "09/07/2026 at 11:00 PM", "09/07/2026 at 11:30 PM"),
            sample("REM", "09/07/2026 at 11:30 PM", "10/07/2026 at 12:00 AM"),
            sample("In Bed", "09/07/2026 at 10:55 PM", "10/07/2026 at 6:00 AM"),
            sample("Martian", "10/07/2026 at 12:00 AM", "10/07/2026 at 1:00 AM"))); // unknown → ignored

        SleepStageSample totals = parser.parse(dump, ZONE, reference()).sleep().totals();

        assertThat(totals.deepSeconds()).isEqualTo(1800);
        assertThat(totals.remSeconds()).isEqualTo(1800);
        assertThat(totals.inBedSeconds()).isEqualTo(7 * 3600 + 5 * 60);
        // The unknown stage contributed no asleep time.
        assertThat(totals.unspecifiedSeconds()).isZero();
    }

    @Test
    @DisplayName("a dump with no parseable samples is rejected (caller skips, keeps replanning)")
    void rejects_dump_without_parseable_samples() {
        DeviceSleepSamples dump = new DeviceSleepSamples("bogus", List.of(
            sample("Core", "not-a-date", "also-not-a-date"),
            sample("Martian", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM")));

        assertThatThrownBy(() -> parser.parse(dump, ZONE, reference()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no parseable sleep samples");
    }

    @Test
    @DisplayName("an absent capture date falls back to the caller's reference instant")
    void absent_capture_date_falls_back_to_the_reference() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM")));

        ParsedSleepDay day = parser.parse(dump, ZONE, reference());

        assertThat(day.collectedAt()).isEqualTo(reference());
    }

    /** The instant a run is anchored on when the dump's own capture date is not what is under test. */
    private static OffsetDateTime reference() {
        return OffsetDateTime.parse("2026-07-10T08:00:00Z");
    }

    private static DeviceSleepSamples.Sample sample(String stage, String start, String end) {
        return new DeviceSleepSamples.Sample(stage, start, end);
    }

    private DeviceSleepSamples loadFixture(String resource) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s on the test classpath", resource).isNotNull();
            JsonNode root = mapper.readTree(in);
            List<DeviceSleepSamples.Sample> samples = new ArrayList<>();
            for (JsonNode node : root.get("sample")) {
                samples.add(new DeviceSleepSamples.Sample(
                    node.get("stage").asText(),
                    node.get("startDate").asText(),
                    node.get("endDate").asText()));
            }
            return new DeviceSleepSamples(root.get("date").asText(), samples);
        }
    }
}
