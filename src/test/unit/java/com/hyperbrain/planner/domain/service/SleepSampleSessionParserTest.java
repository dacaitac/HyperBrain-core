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
    @DisplayName("distils the real Shortcut fixture: the sleep day's night, contested seconds split")
    void parses_real_shortcut_fixture() throws Exception {
        DeviceSleepSamples dump = loadFixture("/fixtures/shortcut_sleep_sample.json");

        ParsedSleepDay day = parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-23T23:00:00Z"));

        // The fixture holds two nights and captures at 23 Jul 22:12; only the night whose start the
        // sleep day holds (22 Jul 23:41 → 23 Jul 07:51) is summed — the one two days back is not.
        SleepStageSample sample = day.sleep().totals();
        assertThat(day.sleep().sessions()).hasSize(1);
        assertThat(day.sleep().mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-22T23:41:00Z"));
        assertThat(day.sleep().mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-23T07:51:00Z"));
        assertThat(sample.start()).isEqualTo(OffsetDateTime.parse("2026-07-22T23:41:00Z"));
        assertThat(sample.end()).isEqualTo(OffsetDateTime.parse("2026-07-23T07:51:00Z"));

        // TST is the union of every asleep interval on one timeline (~7.45 h), NOT the 40 200 s sum of
        // per-stage unions, which would falsely exceed time in bed. The split does not move it.
        long tst = sample.totalSleepSeconds();
        assertThat(tst).isCloseTo(26820L, within(60L));

        // Half the night (13 380 s of 26 820) was claimed by more than one stage at once — this is the
        // number that condemned the old precedence rule. Handing every contested second to the deeper
        // track read this same night as deep=9420 / rem=7920 / core=9480: a deep fraction of 0.35 and a
        // Core residue of 0.35 against an adult N1+N2 norm of 0.45–0.55. Splitting them evenly instead
        // moves the mass back where the physiology says it was, on identical raw data.
        assertThat(day.sleep().overlapSeconds()).isEqualTo(13380);
        assertThat(sample.deepSeconds()).isEqualTo(4800);
        assertThat(sample.remSeconds()).isEqualTo(6810);
        assertThat(sample.coreSeconds()).isEqualTo(15210);
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
    @DisplayName("an evening run keeps the after-18:00 nap the old 24 h clamp used to lose entirely")
    void keeps_the_evening_nap_the_old_lookback_clamp_dropped() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 7:00 PM", "09/07/2026 at 8:00 PM"),    // opens this sleep day
            sample("Core", "09/07/2026 at 11:30 PM", "10/07/2026 at 6:00 AM"))); // the night

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();

        // A 19:00 nap on the 9th is inside the sleep day that opened at 18:00 on the 9th, whichever hour
        // the run happens at. The rolling 24 h clamp used to close the period at 09 Jul 22:00 on an
        // evening run — and since the next morning's window opens at 18:00 on the 10th, no row claimed
        // that hour of sleep at all. It was simply lost.
        assertThat(sleep.sessions()).hasSize(2);
        assertThat(sleep.sessions().getFirst().start())
            .isEqualTo(OffsetDateTime.parse("2026-07-09T19:00:00Z"));
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
    @DisplayName("membership is decided by the session's START: 18:00 sharp is in, a minute before is out")
    void the_sleep_day_holds_a_session_by_its_start() {
        // The boundary decides which row a nap lands on, so which side owns the instant itself is not a
        // detail: an inclusive-on-both-ends bound would put the same nap on two consecutive days.
        AggregatedSleep startsOnTheBoundary = parser.parse(new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 6:00 PM", "09/07/2026 at 7:00 PM"),    // starts AT 18:00
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"))),
            ZONE, reference()).sleep();

        AggregatedSleep startsAMinuteEarlier = parser.parse(new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 5:59 PM", "09/07/2026 at 7:00 PM"),    // starts 17:59
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM"))),
            ZONE, reference()).sleep();

        assertThat(startsOnTheBoundary.sessions()).hasSize(2);
        // Dropped whole, not truncated at 18:00: the session belongs to the previous sleep day entirely.
        assertThat(startsAMinuteEarlier.sessions()).hasSize(1);
    }

    @Test
    @DisplayName("the same nap lands on the same row whatever hour the replan happened to run at")
    void the_sleep_day_of_a_nap_does_not_depend_on_when_the_run_happens() {
        // A nap at 19:00 on the 9th sits inside the sleep day that opens at 18:00 on the 9th, and there
        // is only one answer to which row it belongs to. Under the rolling 24 h clamp the answer depended
        // on the hour of the run: a morning replan reached back to 18:00 and kept it, an evening replan
        // reached back only 24 h and cut it off — same night, same nap, two different rows.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 7:00 PM", "09/07/2026 at 8:00 PM"),
            sample("Core", "09/07/2026 at 11:30 PM", "10/07/2026 at 6:00 AM")));

        AggregatedSleep morningRun =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T08:00:00Z")).sleep();
        AggregatedSleep eveningRun =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();

        assertThat(morningRun.sessions()).isEqualTo(eveningRun.sessions()).hasSize(2);
        assertThat(morningRun.totals().totalSleepSeconds())
            .isEqualTo(eveningRun.totals().totalSleepSeconds());
    }

    @Test
    @DisplayName("a nap taken after 18:00 belongs to tomorrow's row only — never to both")
    void an_evening_nap_belongs_to_exactly_one_sleep_day() {
        // The other half of the same defect. A 19:00 nap on the 10th used to be inside the 24 h an
        // evening run reached back over AND inside the sleep day the 18:00 rule opened for the 11th, so
        // an evening replan and the next morning's replan both counted it, on two different rows. The
        // 18:00 that closes a sleep day is now the same 18:00 that opens the next: exactly one claims it.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:30 PM", "10/07/2026 at 6:00 AM"),  // the night
            sample("Core", "10/07/2026 at 7:00 PM", "10/07/2026 at 8:00 PM"))); // the evening nap

        AggregatedSleep tonight =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T22:00:00Z")).sleep();
        AggregatedSleep tomorrowMorning =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-11T08:00:00Z")).sleep();

        SleepSession night = new SleepSession(OffsetDateTime.parse("2026-07-09T23:30:00Z"),
            OffsetDateTime.parse("2026-07-10T06:00:00Z"), 6 * 3600 + 1800);
        SleepSession nap = new SleepSession(OffsetDateTime.parse("2026-07-10T19:00:00Z"),
            OffsetDateTime.parse("2026-07-10T20:00:00Z"), 3600);
        assertThat(tonight.sessions()).containsExactly(night);
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
    @DisplayName("a night that runs past the hour the sleep day closes is summed whole, not truncated")
    void keeps_a_session_that_straddles_the_closing_boundary() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 9:30 PM", "10/07/2026 at 5:30 AM")));

        AggregatedSleep sleep =
            parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T05:00:00Z")).sleep();

        // A replan at 05:00 closes the sleep day on itself, half an hour before he woke up. The session
        // is still summed entire: membership is decided by its start and the session is then kept whole,
        // so no other row may claim the hours that fall past the boundary.
        assertThat(sleep.mainSession().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T21:30:00Z"));
        assertThat(sleep.mainSession().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T05:30:00Z"));
        assertThat(sleep.totals().coreSeconds()).isEqualTo(8 * 3600);
    }

    @Test
    @DisplayName("a dump whose sleep all belongs to another sleep day is rejected (no stale row)")
    void rejects_a_dump_with_nothing_in_the_sleep_day() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "07/07/2026 at 11:00 PM", "08/07/2026 at 6:00 AM")));

        assertThatThrownBy(() -> parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T08:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no sleep session in the sleep day");
    }

    @Test
    @DisplayName("a dump claiming more asleep + awake time than the window it measured is refused")
    void rejects_more_sleep_than_the_measured_window_can_hold() {
        // Awake laid over the whole first half of the night: the window measures 6 h but the reading
        // claims 9 h of it accounted for. Scoring that would put a number on efficiency and WASO that
        // no measurement supports.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 5:00 AM"),
            sample("Awake", "09/07/2026 at 11:00 PM", "10/07/2026 at 2:00 AM")));

        assertThatThrownBy(() -> parser.parse(dump, ZONE, reference()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceed the 21600s window that was measured");
    }

    @Test
    @DisplayName("a sleep day holding more than 16 h of sleep is refused as a corrupt reading")
    void rejects_more_than_sixteen_hours_of_sleep() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 6:00 PM", "10/07/2026 at 11:00 AM"))); // 17 h straight

        assertThatThrownBy(() -> parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T14:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("of sleep exceed the 16h a sleep day can hold");
    }

    @Test
    @DisplayName("a measured window wider than a day is refused, however little sleep it holds")
    void rejects_a_measured_window_wider_than_a_day() {
        // Two in-bed stretches of 18 h and 8 h sum to a 26 h window. Little of it is asleep, so neither
        // of the other two guards sees anything wrong — yet no sleep day was ever 26 h long.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("In Bed", "09/07/2026 at 6:00 PM", "10/07/2026 at 12:00 PM"),
            sample("Core", "09/07/2026 at 6:00 PM", "09/07/2026 at 8:00 PM"),
            sample("In Bed", "10/07/2026 at 5:00 PM", "11/07/2026 at 1:00 AM"),
            sample("Core", "10/07/2026 at 5:00 PM", "10/07/2026 at 6:00 PM")));

        assertThatThrownBy(() -> parser.parse(dump, ZONE, OffsetDateTime.parse("2026-07-10T23:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("measured window exceeds the 24h of a sleep day");
    }

    @Test
    @DisplayName("splits a contested instant evenly among the stages covering it, never by precedence")
    void splits_a_contested_instant_proportionally() {
        // Core spans the whole 2 h; Deep and REM are revisions the watch laid on top of part of it.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 1:00 AM"),   // 23:00–01:00
            sample("Deep", "09/07/2026 at 11:30 PM", "10/07/2026 at 12:00 AM"),  // 30 m over Core
            sample("REM", "10/07/2026 at 12:00 AM", "10/07/2026 at 12:15 AM"))); // 15 m over Core

        ParsedSleepDay day = parser.parse(dump, ZONE, reference());
        SleepStageSample s = day.sleep().totals();

        // 23:00–23:30 Core alone (1800) + 23:30–00:00 halved with Deep (900) + 00:00–00:15 halved with
        // REM (450) + 00:15–01:00 Core alone (2700). Deepest-wins gave Core 4500 and handed the deeper
        // tracks the full 2700 contested seconds; the overlap says nothing about which stage was right.
        assertThat(s.coreSeconds()).isEqualTo(1800 + 900 + 450 + 2700);
        assertThat(s.deepSeconds()).isEqualTo(900);
        assertThat(s.remSeconds()).isEqualTo(450);
        assertThat(s.totalSleepSeconds()).isEqualTo(2 * 3600);
        assertThat(day.sleep().overlapSeconds()).isEqualTo(30 * 60 + 15 * 60);
    }

    @Test
    @DisplayName("the four stages sum exactly to the asleep union, however many of them pile up")
    void the_stages_always_sum_to_total_sleep_time() {
        // The invariant that is not negotiable: whatever the split does to the mix, no second of sleep
        // may be counted twice nor lost. Four tracks over the same hour is the worst case the watch
        // produces after a couple of restagings.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "10/07/2026 at 12:00 AM", "10/07/2026 at 1:00 AM"),
            sample("Deep", "10/07/2026 at 12:00 AM", "10/07/2026 at 1:00 AM"),
            sample("REM", "10/07/2026 at 12:00 AM", "10/07/2026 at 1:00 AM"),
            sample("Asleep", "10/07/2026 at 12:00 AM", "10/07/2026 at 1:00 AM")));

        ParsedSleepDay day = parser.parse(dump, ZONE, reference());
        SleepStageSample s = day.sleep().totals();

        assertThat(s.coreSeconds()).isEqualTo(900);
        assertThat(s.deepSeconds()).isEqualTo(900);
        assertThat(s.remSeconds()).isEqualTo(900);
        assertThat(s.unspecifiedSeconds()).isEqualTo(900);
        assertThat(s.coreSeconds() + s.deepSeconds() + s.remSeconds() + s.unspecifiedSeconds())
            .isEqualTo(s.totalSleepSeconds())
            .isEqualTo(3600);
        // The whole hour was contested — that is what the measurement is for.
        assertThat(day.sleep().overlapSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("a night no stage contested measures zero overlap")
    void an_uncontested_night_measures_no_overlap() {
        // Without this the measurement would be unreadable: a number that is never zero says nothing
        // about the nights that actually were re-staged.
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 2:00 AM"),
            sample("Deep", "10/07/2026 at 2:00 AM", "10/07/2026 at 3:00 AM"),
            sample("REM", "10/07/2026 at 3:00 AM", "10/07/2026 at 4:00 AM")));

        ParsedSleepDay day = parser.parse(dump, ZONE, reference());

        assertThat(day.sleep().overlapSeconds()).isZero();
        assertThat(day.sleep().totals().coreSeconds()).isEqualTo(3 * 3600);
        assertThat(day.sleep().totals().deepSeconds()).isEqualTo(3600);
        assertThat(day.sleep().totals().remSeconds()).isEqualTo(3600);
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
