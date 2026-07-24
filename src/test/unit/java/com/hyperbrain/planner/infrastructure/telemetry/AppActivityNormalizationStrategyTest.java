package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.AppUsageBucket;
import com.hyperbrain.planner.domain.port.out.AppUsageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("AppActivityNormalizationStrategy — DEVICE_ACTIVITY/APP_ACTIVITY → tel_app_usage")
class AppActivityNormalizationStrategyTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime COLLECTED = OffsetDateTime.parse("2026-07-11T07:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AppUsageStore appUsageStore;
    private AppActivityNormalizationStrategy strategy;

    @BeforeEach
    void setUp() {
        appUsageStore = mock(AppUsageStore.class);
        strategy = new AppActivityNormalizationStrategy(appUsageStore);
    }

    @Test
    @DisplayName("routes on the DEVICE_ACTIVITY/APP_ACTIVITY pair")
    void routes_on_provider_and_type() {
        assertThat(strategy.provider()).isEqualTo("DEVICE_ACTIVITY");
        assertThat(strategy.eventType()).isEqualTo("APP_ACTIVITY");
    }

    @Test
    @DisplayName("each bucket becomes one row; pickups are optional and the raw trace is carried")
    void buckets_map_to_rows() {
        String payload = """
            { "buckets": [
                { "bucket_start": "2026-07-11T08:00:00Z", "bucket_end": "2026-07-11T09:00:00Z",
                  "category": "SOCIAL", "duration_seconds": 1200, "pickups": 15 },
                { "bucket_start": "2026-07-11T09:00:00Z", "bucket_end": "2026-07-11T10:00:00Z",
                  "category": "PRODUCTIVITY", "duration_seconds": 3000 }
              ] }
            """;

        strategy.normalize(record(payload));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AppUsageBucket>> captor = ArgumentCaptor.forClass(List.class);
        verify(appUsageStore).saveBuckets(eq(USER), captor.capture(), eq(CONTEXT_EVENT), eq(COLLECTED));
        List<AppUsageBucket> buckets = captor.getValue();
        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).category()).isEqualTo("SOCIAL");
        assertThat(buckets.get(0).durationSeconds()).isEqualTo(1200);
        assertThat(buckets.get(0).pickups()).isEqualTo(15);
        assertThat(buckets.get(0).bucketStart()).isEqualTo(OffsetDateTime.parse("2026-07-11T08:00:00Z"));
        assertThat(buckets.get(1).category()).isEqualTo("PRODUCTIVITY");
        assertThat(buckets.get(1).pickups()).isNull();
    }

    @Test
    @DisplayName("an empty buckets array normalizes to zero rows (still a no-op save)")
    void empty_buckets_saves_empty() {
        strategy.normalize(record("{ \"buckets\": [] }"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AppUsageBucket>> captor = ArgumentCaptor.forClass(List.class);
        verify(appUsageStore).saveBuckets(eq(USER), captor.capture(), eq(CONTEXT_EVENT), eq(COLLECTED));
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("a missing buckets array is a contract violation (ERROR) with no write")
    void missing_buckets_fails_without_write() {
        assertThatThrownBy(() -> strategy.normalize(record("{ \"foo\": 1 }")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("buckets");
        verifyNoInteractions(appUsageStore);
    }

    @Test
    @DisplayName("a bucket missing its category fails before any write")
    void bucket_missing_category_fails_without_write() {
        String payload = """
            { "buckets": [
                { "bucket_start": "2026-07-11T08:00:00Z", "bucket_end": "2026-07-11T09:00:00Z",
                  "duration_seconds": 1200 } ] }
            """;

        assertThatThrownBy(() -> strategy.normalize(record(payload)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("category");
        verify(appUsageStore, org.mockito.Mockito.never()).saveBuckets(any(), anyList(), any(), any());
    }

    private TelemetryRecord record(String payloadJson) {
        return new TelemetryRecord(USER, CONTEXT_EVENT, "DEVICE_ACTIVITY", "APP_ACTIVITY", "1",
            OffsetDateTime.parse("2026-07-11T10:00:00Z"), COLLECTED, readTree(payloadJson), ZoneOffset.UTC);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
