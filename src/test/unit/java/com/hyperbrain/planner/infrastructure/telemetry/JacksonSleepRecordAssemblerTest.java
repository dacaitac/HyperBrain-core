package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JacksonSleepRecordAssembler — AggregatedSleep → scored DeviceSleepRecord")
class JacksonSleepRecordAssemblerTest {

    private static final UUID CONTEXT_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime COLLECTED = OffsetDateTime.parse("2026-07-11T07:00:00Z");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-07-10T22:00:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-07-11T06:30:00Z");

    private final JacksonSleepRecordAssembler assembler =
        new JacksonSleepRecordAssembler(new SleepScoreCalculator(), new ObjectMapper());

    @Test
    @DisplayName("scores an ideal night to 100, derives duration, and serializes the stage breakdown")
    void assembles_complete_record_with_raw_trace() {
        // TST 8h (core 17280, deep 5184, rem 6336), TIB 8.5h, WASO 10min → score 100.
        SleepStageSample sample = new SleepStageSample(START, END, 0, 17280, 5184, 6336, 0, 600);

        DeviceSleepRecord record =
            assembler.assemble(AggregatedSleep.ofSingleSession(sample), COLLECTED, CONTEXT_EVENT);

        assertThat(record.startTime()).isEqualTo(START);
        assertThat(record.endTime()).isEqualTo(END);
        assertThat(record.durationMinutes()).isEqualTo(480);
        assertThat(record.sleepScore()).isEqualTo(100);
        assertThat(record.collectedAt()).isEqualTo(COLLECTED);
        assertThat(record.contextEventId()).isEqualTo(CONTEXT_EVENT);
        assertThat(record.stagesJson())
            .contains("\"low_confidence\":false")
            .contains("\"deep_seconds\":5184")
            .contains("\"sub_scores\"");
    }

    @Test
    @DisplayName("a null context_event id is carried through (user-command sleep bridge)")
    void assembles_record_without_raw_origin() {
        SleepStageSample sample = new SleepStageSample(START, END, 0, 17280, 5184, 6336, 0, 600);

        DeviceSleepRecord record = assembler.assemble(AggregatedSleep.ofSingleSession(sample), COLLECTED, null);

        assertThat(record.contextEventId()).isNull();
        assertThat(record.sleepScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("a session with no stage breakdown scores low-confidence, never 0")
    void assembles_low_confidence_record_without_phases() {
        // Only unspecified asleep time, TIB 10h, WASO 10min → duration+efficiency 60/40, low confidence.
        SleepStageSample sample = new SleepStageSample(
            START, OffsetDateTime.parse("2026-07-11T08:00:00Z"), 0, 0, 0, 0, 28800, 600);

        DeviceSleepRecord record = assembler.assemble(AggregatedSleep.ofSingleSession(sample), COLLECTED, null);

        assertThat(record.sleepScore()).isGreaterThan(0);
        assertThat(record.stagesJson()).contains("\"low_confidence\":true");
    }

    @Test
    @DisplayName("the totals' window is spent on durations only — never on the row's hours")
    void the_duration_carrying_window_never_becomes_a_clock() {
        // The one thing about the aggregate that is not literally true: when a night and a nap are
        // summed, the totals' window opens at the night's start and lasts the SUMMED time in bed, so it
        // ends at an instant nobody was ever asleep at. This is the assertion that keeps that fiction
        // out of everything downstream — the row's two instant columns are the chronotype the sleep
        // frontier learns its wake median from, and the sessions array is what the model is shown.
        OffsetDateTime nightStart = OffsetDateTime.parse("2026-07-10T23:00:00Z");
        OffsetDateTime nightEnd = OffsetDateTime.parse("2026-07-11T05:00:00Z");   // 6 h in bed
        OffsetDateTime napStart = OffsetDateTime.parse("2026-07-11T14:00:00Z");
        OffsetDateTime napEnd = OffsetDateTime.parse("2026-07-11T16:00:00Z");     // 2 h in bed
        SleepSession night = new SleepSession(nightStart, nightEnd, 5 * 3600);
        SleepSession nap = new SleepSession(napStart, napEnd, 2 * 3600);
        // 8 h of summed time in bed hung off the night's start: the carrier ends at 07:00, an hour at
        // which he was awake and at his desk.
        OffsetDateTime fictitiousEnd = nightStart.plusHours(8);
        SleepStageSample totals =
            new SleepStageSample(nightStart, fictitiousEnd, 0, 18000, 3600, 3600, 0, 1800);

        DeviceSleepRecord record = assembler.assemble(
            new AggregatedSleep(totals, night, List.of(night, nap)), COLLECTED, null);

        // The row's hours are the main session's, to the second — not the carrier's.
        assertThat(record.startTime()).isEqualTo(nightStart);
        assertThat(record.endTime()).isEqualTo(nightEnd);
        assertThat(record.endTime()).isNotEqualTo(fictitiousEnd);
        // And the fictitious instant appears nowhere in what is persisted: the sessions array carries
        // the real hours of both sleeps, so no later reader can mistake the carrier for a clock.
        assertThat(record.stagesJson())
            .doesNotContain(fictitiousEnd.toString())
            .contains(nightStart.toString(), nightEnd.toString(), napStart.toString(), napEnd.toString())
            .contains("\"asleep_seconds\":18000")
            .contains("\"asleep_seconds\":7200");
        // The durations, meanwhile, ARE read off the carrier: 7 h of sleep over 8 h of summed bed time.
        assertThat(record.durationMinutes()).isEqualTo(7 * 60);
        assertThat(record.stagesJson()).contains("\"efficiency\":0.875");
    }

    @Test
    @DisplayName("every session survives into the stored breakdown, in the order they happened")
    void the_sessions_survive_into_the_stored_breakdown() {
        // The row has exactly two instant columns and the main session owns them, so this array is the
        // only place a nap exists once the dump is gone — and the only thing the day can later read to
        // know WHEN he slept.
        SleepSession night = new SleepSession(START, END, 6 * 3600);
        SleepSession nap = new SleepSession(
            END.plusHours(6), END.plusHours(7), 3000);
        SleepStageSample totals = new SleepStageSample(
            START, START.plusHours(9), 0, 17280, 5184, 6336, 0, 600);

        DeviceSleepRecord record = assembler.assemble(
            new AggregatedSleep(totals, night, List.of(night, nap)), COLLECTED, null);

        assertThat(record.stagesJson()).contains(
            "\"sessions\":["
                + "{\"start\":\"" + START + "\",\"end\":\"" + END + "\",\"asleep_seconds\":21600},"
                + "{\"start\":\"" + nap.start() + "\",\"end\":\"" + nap.end()
                + "\",\"asleep_seconds\":3000}]");
    }

    @Test
    @DisplayName("a broken night stamps the row with the WHOLE night, hole and all — never the longer half")
    void a_broken_night_stamps_the_row_with_its_full_span() {
        // The row's two instant columns are the chronotype the sleep frontier learns from, so on a night
        // slept in two phases they have to span both: stamping the longer fragment alone declared that
        // he got up at 03:00. The duration carrier is the other half of the same separation — it must
        // NOT grow to the 9 h span, or the 3 h 30 hole would be counted as time in bed and sink
        // efficiency for a night he simply woke up in the middle of.
        OffsetDateTime nightStart = OffsetDateTime.parse("2026-08-07T23:00:00Z");
        OffsetDateTime nightEnd = OffsetDateTime.parse("2026-08-08T08:00:00Z");
        SleepSession firstHalf = new SleepSession(nightStart, nightStart.plusHours(4), 4 * 3600);
        SleepSession secondHalf = new SleepSession(nightEnd.minusHours(1).minusMinutes(30),
            nightEnd, 90 * 60);
        SleepSession night = new SleepSession(nightStart, nightEnd, 4 * 3600 + 90 * 60);
        SleepStageSample carrier = new SleepStageSample(
            nightStart, nightStart.plusSeconds(4 * 3600 + 90 * 60), 0, 19800, 0, 0, 0, 0);

        DeviceSleepRecord record = assembler.assemble(
            new AggregatedSleep(carrier, night, List.of(firstHalf, secondHalf)), COLLECTED, null);

        assertThat(record.startTime()).isEqualTo(nightStart);
        assertThat(record.endTime()).isEqualTo(nightEnd);
        // 5 h 30 of sleep over 5 h 30 of summed bed time — the hole is neither slept nor in bed.
        assertThat(record.durationMinutes()).isEqualTo(330);
        assertThat(record.stagesJson()).contains("\"efficiency\":1.0");
        // And both fragments survive, so the day can still see WHEN the night was broken.
        assertThat(record.stagesJson())
            .contains(firstHalf.end().toString(), secondHalf.start().toString());
    }

    @Test
    @DisplayName("the contested seconds are persisted beside the stages, so past rows can be judged")
    void the_overlap_measurement_is_persisted() {
        // Not a duration of the night but a property of the reading: how much of the sleep more than one
        // stage claimed, which is what the proportional split had to divide. Without it on the row there
        // is no way to tell a night the watch read cleanly from one it re-staged three times, and no way
        // to recalibrate the score afterwards against the rows already written.
        SleepSession night = new SleepSession(START, END, 6 * 3600);
        SleepStageSample totals = new SleepStageSample(
            START, END, 0, 17280, 5184, 6336, 0, 600);

        DeviceSleepRecord contested = assembler.assemble(
            new AggregatedSleep(totals, night, List.of(night), 13380), COLLECTED, null);
        DeviceSleepRecord clean = assembler.assemble(
            new AggregatedSleep(totals, night, List.of(night)), COLLECTED, null);

        assertThat(contested.stagesJson()).contains("\"overlap_seconds\":13380");
        assertThat(clean.stagesJson()).contains("\"overlap_seconds\":0");
        // And it changes no total: the stages are split over the overlap, never summed twice.
        assertThat(contested.durationMinutes()).isEqualTo(clean.durationMinutes());
        assertThat(contested.sleepScore()).isEqualTo(clean.sleepScore());
    }

    @Test
    @DisplayName("a night with no asleep time is not scorable and is rejected before building a record")
    void rejects_unscorable_night() {
        SleepStageSample sample = new SleepStageSample(START, END, 0, 0, 0, 0, 0, 3600);

        assertThatThrownBy(() -> assembler.assemble(AggregatedSleep.ofSingleSession(sample), COLLECTED, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not scorable");
    }
}
