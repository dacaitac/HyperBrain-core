package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("SleepSessionNormalizationStrategy — APPLE_HEALTH/SLEEP_SESSION → tel_sleep_record")
class SleepSessionNormalizationStrategyTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime COLLECTED = OffsetDateTime.parse("2026-07-11T07:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private SleepScoreStore sleepScoreStore;
    private SleepSessionNormalizationStrategy strategy;

    @BeforeEach
    void setUp() {
        sleepScoreStore = mock(SleepScoreStore.class);
        strategy = new SleepSessionNormalizationStrategy(
            new SleepScoreCalculator(), sleepScoreStore, objectMapper);
    }

    @Test
    @DisplayName("routes on the APPLE_HEALTH/SLEEP_SESSION pair")
    void routes_on_provider_and_type() {
        assertThat(strategy.provider()).isEqualTo("APPLE_HEALTH");
        assertThat(strategy.eventType()).isEqualTo("SLEEP_SESSION");
    }

    @Test
    @DisplayName("an ideal session persists a complete device record (real hours, score 100, stages) with the raw trace")
    void ideal_session_persists_device_record() {
        // TST 8h (core 17280, deep 5184, rem 6336), TIB 8.5h, WASO 10min → score 100.
        String payload = """
            { "start_time": "2026-07-10T22:00:00Z", "end_time": "2026-07-11T06:30:00Z",
              "core_seconds": 17280, "deep_seconds": 5184, "rem_seconds": 6336, "awake_seconds": 600 }
            """;

        strategy.normalize(record(payload));

        ArgumentCaptor<DeviceSleepRecord> captor = ArgumentCaptor.forClass(DeviceSleepRecord.class);
        verify(sleepScoreStore).upsertDeviceSleepRecord(eq(USER), captor.capture(), eq(ZONE));
        DeviceSleepRecord persisted = captor.getValue();
        assertThat(persisted.startTime()).isEqualTo(OffsetDateTime.parse("2026-07-10T22:00:00Z"));
        assertThat(persisted.endTime()).isEqualTo(OffsetDateTime.parse("2026-07-11T06:30:00Z"));
        assertThat(persisted.durationMinutes()).isEqualTo(480);
        assertThat(persisted.sleepScore()).isEqualTo(100);
        assertThat(persisted.collectedAt()).isEqualTo(COLLECTED);
        assertThat(persisted.contextEventId()).isEqualTo(CONTEXT_EVENT);
        assertThat(persisted.stagesJson())
            .contains("\"low_confidence\":false")
            .contains("\"deep_seconds\":5184")
            .contains("\"sub_scores\"");
    }

    @Test
    @DisplayName("a session with no stage breakdown persists a low-confidence score, never 0")
    void no_phase_breakdown_persists_low_confidence() {
        // Only unspecified asleep time, TIB 10h → duration+efficiency 60/40, low confidence.
        String payload = """
            { "start_time": "2026-07-10T22:00:00Z", "end_time": "2026-07-11T08:00:00Z",
              "unspecified_seconds": 28800 }
            """;

        strategy.normalize(record(payload));

        ArgumentCaptor<DeviceSleepRecord> captor = ArgumentCaptor.forClass(DeviceSleepRecord.class);
        verify(sleepScoreStore).upsertDeviceSleepRecord(eq(USER), captor.capture(), eq(ZONE));
        DeviceSleepRecord persisted = captor.getValue();
        assertThat(persisted.sleepScore()).isGreaterThan(0);
        assertThat(persisted.stagesJson()).contains("\"low_confidence\":true");
    }

    @Test
    @DisplayName("a missing end_time fails before any write (tolerant-reader ERROR)")
    void missing_end_time_fails_without_write() {
        String payload = """
            { "start_time": "2026-07-10T22:00:00Z", "core_seconds": 17280 }
            """;

        assertThatThrownBy(() -> strategy.normalize(record(payload)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sleepScoreStore);
    }

    @Test
    @DisplayName("a session with no asleep time fails before any write (not scorable)")
    void no_sleep_fails_without_write() {
        String payload = """
            { "start_time": "2026-07-10T22:00:00Z", "end_time": "2026-07-11T06:30:00Z",
              "awake_seconds": 3600 }
            """;

        assertThatThrownBy(() -> strategy.normalize(record(payload)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not scorable");
        verifyNoInteractions(sleepScoreStore);
    }

    private TelemetryRecord record(String payloadJson) {
        JsonNode payload = readTree(payloadJson);
        return new TelemetryRecord(USER, CONTEXT_EVENT, "APPLE_HEALTH", "SLEEP_SESSION", "1",
            OffsetDateTime.parse("2026-07-11T06:30:00Z"), COLLECTED, payload, ZONE);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
