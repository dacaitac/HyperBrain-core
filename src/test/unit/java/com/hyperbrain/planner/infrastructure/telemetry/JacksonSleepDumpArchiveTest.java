package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.NormalizationStatus;
import com.hyperbrain.planner.domain.model.RawTelemetryRow;
import com.hyperbrain.planner.domain.port.out.RawTelemetryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JacksonSleepDumpArchive — the HealthKit dump kept verbatim, one row per night")
class JacksonSleepDumpArchiveTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RAW_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate SLEEP_DAY = LocalDate.of(2026, 8, 8);
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-08-08T12:05:00Z");
    /** Narrow no-break space (U+202F): exactly what Apple inserts before AM/PM. */
    private static final char NNBSP = '\u202f';

    private RawTelemetryStore rawTelemetryStore;
    private JacksonSleepDumpArchive archive;

    @BeforeEach
    void setUp() {
        rawTelemetryStore = mock(RawTelemetryStore.class);
        archive = new JacksonSleepDumpArchive(rawTelemetryStore, new ObjectMapper());
    }

    @Test
    @DisplayName("stores the dump in the Shortcut's own shape, local time strings untouched")
    void the_payload_is_the_dump_as_it_arrived() {
        // The whole value of the archive is that it can be read back through the parser exactly as the
        // command read it forward — so the narrow no-break space must survive, not be tidied away.
        DeviceSleepSamples dump = new DeviceSleepSamples(
            "08/08/2026 at 12:05" + NNBSP + "PM",
            List.of(new DeviceSleepSamples.Sample(
                "Core", "07/08/2026 at 11:00" + NNBSP + "PM", "08/08/2026 at 3:00" + NNBSP + "AM")));
        when(rawTelemetryStore.upsertLatest(any())).thenReturn(RAW_ID);

        UUID archived = archive.archive(USER_ID, dump, SLEEP_DAY, OCCURRED_AT);

        assertThat(archived).isEqualTo(RAW_ID);
        RawTelemetryRow row = capturedRow();
        assertThat(row.payloadJson())
            .isEqualTo("{\"date\":\"08/08/2026 at 12:05" + NNBSP + "PM\",\"sample\":["
                + "{\"stage\":\"Core\",\"startDate\":\"07/08/2026 at 11:00" + NNBSP + "PM\","
                + "\"endDate\":\"08/08/2026 at 3:00" + NNBSP + "AM\"}]}");
        assertThat(row.userId()).isEqualTo(USER_ID);
        assertThat(row.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    @DisplayName("files it under APPLE_HEALTH/SLEEP_STAGE_DUMP, never the canonical SLEEP_SESSION type")
    void the_event_type_does_not_collide_with_the_canonical_payload_shape() {
        // The canonical APPLE_HEALTH/SLEEP_SESSION payload is one aggregated session; this is a list of
        // raw stage intervals. Sharing the type would let a re-normalizer hand one shape to the strategy
        // written for the other.
        when(rawTelemetryStore.upsertLatest(any())).thenReturn(RAW_ID);

        archive.archive(USER_ID, dumpOfOneSample(), SLEEP_DAY, OCCURRED_AT);

        RawTelemetryRow row = capturedRow();
        assertThat(row.provider()).isEqualTo("APPLE_HEALTH");
        assertThat(row.eventType()).isEqualTo("SLEEP_STAGE_DUMP");
        assertThat(row.eventType()).isNotEqualTo(SleepSessionNormalizationStrategy.EVENT_TYPE);
        assertThat(row.schemaVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("the key is the user's night, so a re-sent dump overwrites instead of piling up")
    void the_archive_key_is_the_user_and_the_night() {
        // Not a content hash: the point is precisely that the payload for one night keeps changing as
        // the watch re-stages it, and only the newest reading is worth keeping.
        when(rawTelemetryStore.upsertLatest(any())).thenReturn(RAW_ID);

        archive.archive(USER_ID, dumpOfOneSample(), SLEEP_DAY, OCCURRED_AT);
        archive.archive(USER_ID, dumpOfOneSample(), SLEEP_DAY.plusDays(1), OCCURRED_AT);

        ArgumentCaptor<RawTelemetryRow> rows = ArgumentCaptor.forClass(RawTelemetryRow.class);
        verify(rawTelemetryStore, times(2)).upsertLatest(rows.capture());
        assertThat(rows.getAllValues().getFirst().dedupKey())
            .isEqualTo("APPLE_HEALTH:SLEEP_STAGE_DUMP:" + USER_ID + ":2026-08-08");
        assertThat(rows.getAllValues().getLast().dedupKey())
            .isEqualTo("APPLE_HEALTH:SLEEP_STAGE_DUMP:" + USER_ID + ":2026-08-09");
    }

    @Test
    @DisplayName("the outcome is written back on the raw row: interpreted, or kept for diagnosis")
    void the_outcome_is_recorded_on_the_raw_row() {
        archive.markInterpreted(RAW_ID);
        archive.markUnusable(RAW_ID);

        verify(rawTelemetryStore).markStatus(RAW_ID, NormalizationStatus.NORMALIZED);
        // ERROR and not SKIPPED: the retention sweep spares ERROR rows, and a dump the guards refused
        // is the one row that explains why a night has no score.
        verify(rawTelemetryStore).markStatus(RAW_ID, NormalizationStatus.ERROR);
    }

    private RawTelemetryRow capturedRow() {
        ArgumentCaptor<RawTelemetryRow> row = ArgumentCaptor.forClass(RawTelemetryRow.class);
        verify(rawTelemetryStore).upsertLatest(row.capture());
        return row.getValue();
    }

    private static DeviceSleepSamples dumpOfOneSample() {
        return new DeviceSleepSamples("08/08/2026 at 12:05 PM", List.of(
            new DeviceSleepSamples.Sample(
                "Core", "07/08/2026 at 11:00 PM", "08/08/2026 at 3:00 AM")));
    }
}
