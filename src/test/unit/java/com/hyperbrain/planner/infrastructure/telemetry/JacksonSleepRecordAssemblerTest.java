package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JacksonSleepRecordAssembler — SleepStageSample → scored DeviceSleepRecord")
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

        DeviceSleepRecord record = assembler.assemble(sample, COLLECTED, CONTEXT_EVENT);

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

        DeviceSleepRecord record = assembler.assemble(sample, COLLECTED, null);

        assertThat(record.contextEventId()).isNull();
        assertThat(record.sleepScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("a session with no stage breakdown scores low-confidence, never 0")
    void assembles_low_confidence_record_without_phases() {
        // Only unspecified asleep time, TIB 10h → duration+efficiency 60/40, low confidence.
        SleepStageSample sample = new SleepStageSample(
            START, OffsetDateTime.parse("2026-07-11T08:00:00Z"), 0, 0, 0, 0, 28800, 0);

        DeviceSleepRecord record = assembler.assemble(sample, COLLECTED, null);

        assertThat(record.sleepScore()).isGreaterThan(0);
        assertThat(record.stagesJson()).contains("\"low_confidence\":true");
    }

    @Test
    @DisplayName("a night with no asleep time is not scorable and is rejected before building a record")
    void rejects_unscorable_night() {
        SleepStageSample sample = new SleepStageSample(START, END, 0, 0, 0, 0, 0, 3600);

        assertThatThrownBy(() -> assembler.assemble(sample, COLLECTED, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not scorable");
    }
}
