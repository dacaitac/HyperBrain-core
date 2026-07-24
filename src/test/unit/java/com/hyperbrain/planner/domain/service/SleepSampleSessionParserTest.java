package com.hyperbrain.planner.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.ParsedSleepNight;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SleepSampleSessionParser — raw HealthKit dump → most recent scorable night")
class SleepSampleSessionParserTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    // Narrow no-break space (U+202F): exactly what Apple inserts before AM/PM in the Shortcut dump.
    private static final char NNBSP = '\u202f';

    private final SleepSampleSessionParser parser = new SleepSampleSessionParser();

    @Test
    @DisplayName("distils the real Shortcut fixture: most-recent night, per-stage interval union")
    void parses_real_shortcut_fixture() throws Exception {
        DeviceSleepSamples dump = loadFixture("/fixtures/shortcut_sleep_sample.json");

        ParsedSleepNight night = parser.parse(dump, ZONE);

        // The fixture holds two nights; the parser keeps the most recent (22 Jul 23:41 → 23 Jul 07:51)
        // and unions each stage's overlapping intervals so revised stages are never double-counted.
        SleepStageSample sample = night.sample();
        assertThat(sample.start()).isEqualTo(OffsetDateTime.parse("2026-07-22T23:41:00Z"));
        assertThat(sample.end()).isEqualTo(OffsetDateTime.parse("2026-07-23T07:51:00Z"));
        assertThat(sample.coreSeconds()).isEqualTo(20940);
        assertThat(sample.deepSeconds()).isEqualTo(9420);
        assertThat(sample.remSeconds()).isEqualTo(9840);
        assertThat(sample.awakeSeconds()).isEqualTo(3180);
        assertThat(sample.inBedSeconds()).isEqualTo(28920);
        assertThat(sample.unspecifiedSeconds()).isZero();
        // The capture date ("23/07/2026 at 10:12 PM", with U+202F) is the collection instant.
        assertThat(night.collectedAt()).isEqualTo(OffsetDateTime.parse("2026-07-23T22:12:00Z"));
    }

    @Test
    @DisplayName("normalizes the U+202F narrow no-break space before AM/PM when parsing local times")
    void parses_local_times_with_narrow_no_break_space() {
        DeviceSleepSamples dump = new DeviceSleepSamples(
            "10/07/2026 at 7:30" + NNBSP + "AM",
            List.of(new DeviceSleepSamples.Sample(
                "Core", "09/07/2026 at 11:00" + NNBSP + "PM", "10/07/2026 at 6:00" + NNBSP + "AM")));

        ParsedSleepNight night = parser.parse(dump, ZONE);

        assertThat(night.sample().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(night.sample().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T06:00:00Z"));
        assertThat(night.sample().coreSeconds()).isEqualTo(7 * 3600);
        assertThat(night.collectedAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T07:30:00Z"));
    }

    @Test
    @DisplayName("keeps only the most recent session when nights are separated by a long gap")
    void selects_most_recent_session() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "08/07/2026 at 11:00 PM", "09/07/2026 at 5:00 AM"),   // older night
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 4:00 AM"),   // most recent night
            sample("Deep", "10/07/2026 at 4:00 AM", "10/07/2026 at 5:00 AM")));

        ParsedSleepNight night = parser.parse(dump, ZONE);

        assertThat(night.sample().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(night.sample().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T05:00:00Z"));
        assertThat(night.sample().coreSeconds()).isEqualTo(5 * 3600);
        assertThat(night.sample().deepSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("unions overlapping same-stage intervals instead of summing them")
    void unions_overlapping_intervals() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 1:00 AM"),   // 2h
            sample("Core", "10/07/2026 at 12:30 AM", "10/07/2026 at 2:00 AM"))); // overlaps 30m

        ParsedSleepNight night = parser.parse(dump, ZONE);

        // Union 23:00 → 02:00 = 3h, not the naive 3.5h sum.
        assertThat(night.sample().coreSeconds()).isEqualTo(3 * 3600);
        assertThat(night.sample().start()).isEqualTo(OffsetDateTime.parse("2026-07-09T23:00:00Z"));
        assertThat(night.sample().end()).isEqualTo(OffsetDateTime.parse("2026-07-10T02:00:00Z"));
    }

    @Test
    @DisplayName("maps stage labels case-insensitively and ignores unknown stages")
    void maps_stages_and_ignores_unknown() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("deep", "09/07/2026 at 11:00 PM", "09/07/2026 at 11:30 PM"),
            sample("REM", "09/07/2026 at 11:30 PM", "10/07/2026 at 12:00 AM"),
            sample("In Bed", "09/07/2026 at 10:55 PM", "10/07/2026 at 6:00 AM"),
            sample("Martian", "10/07/2026 at 12:00 AM", "10/07/2026 at 1:00 AM"))); // unknown → ignored

        ParsedSleepNight night = parser.parse(dump, ZONE);

        assertThat(night.sample().deepSeconds()).isEqualTo(1800);
        assertThat(night.sample().remSeconds()).isEqualTo(1800);
        assertThat(night.sample().inBedSeconds()).isEqualTo(7 * 3600 + 5 * 60);
        // The unknown stage contributed no asleep time.
        assertThat(night.sample().unspecifiedSeconds()).isZero();
    }

    @Test
    @DisplayName("a dump with no parseable samples is rejected (caller skips, keeps replanning)")
    void rejects_dump_without_parseable_samples() {
        DeviceSleepSamples dump = new DeviceSleepSamples("bogus", List.of(
            sample("Core", "not-a-date", "also-not-a-date"),
            sample("Martian", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM")));

        assertThatThrownBy(() -> parser.parse(dump, ZONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no parseable sleep samples");
    }

    @Test
    @DisplayName("an absent capture date leaves collectedAt null (caller falls back to occurred_at)")
    void absent_capture_date_yields_null_collected_at() {
        DeviceSleepSamples dump = new DeviceSleepSamples(null, List.of(
            sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 6:00 AM")));

        ParsedSleepNight night = parser.parse(dump, ZONE);

        assertThat(night.collectedAt()).isNull();
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
