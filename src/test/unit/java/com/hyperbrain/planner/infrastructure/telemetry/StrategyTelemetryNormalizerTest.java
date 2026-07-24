package com.hyperbrain.planner.infrastructure.telemetry;

import com.hyperbrain.planner.domain.model.NormalizationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StrategyTelemetryNormalizer — dispatch, SKIPPED and captured ERROR")
class StrategyTelemetryNormalizerTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("a matching strategy runs and reports NORMALIZED")
    void matching_strategy_normalizes() {
        TelemetryNormalizationStrategy strategy = strategy("APPLE_HEALTH", "SLEEP_SESSION");
        StrategyTelemetryNormalizer normalizer = new StrategyTelemetryNormalizer(List.of(strategy));

        TelemetryRecord record = record("APPLE_HEALTH", "SLEEP_SESSION");
        NormalizationStatus status = normalizer.normalize(record);

        assertThat(status).isEqualTo(NormalizationStatus.NORMALIZED);
        verify(strategy).normalize(record);
    }

    @Test
    @DisplayName("no matching strategy is SKIPPED — the raw row is kept, never DLQ'd")
    void no_strategy_is_skipped() {
        TelemetryNormalizationStrategy strategy = strategy("APPLE_HEALTH", "SLEEP_SESSION");
        StrategyTelemetryNormalizer normalizer = new StrategyTelemetryNormalizer(List.of(strategy));

        NormalizationStatus status = normalizer.normalize(record("RESCUETIME", "APP_ACTIVITY"));

        assertThat(status).isEqualTo(NormalizationStatus.SKIPPED);
        verify(strategy, never()).normalize(any());
    }

    @Test
    @DisplayName("a strategy failure is captured and reported ERROR, never propagated")
    void strategy_failure_is_captured_as_error() {
        TelemetryNormalizationStrategy strategy = strategy("APPLE_HEALTH", "SLEEP_SESSION");
        doThrow(new IllegalArgumentException("bad payload")).when(strategy).normalize(any());
        StrategyTelemetryNormalizer normalizer = new StrategyTelemetryNormalizer(List.of(strategy));

        NormalizationStatus status = normalizer.normalize(record("APPLE_HEALTH", "SLEEP_SESSION"));

        assertThat(status).isEqualTo(NormalizationStatus.ERROR);
    }

    private static TelemetryNormalizationStrategy strategy(String provider, String eventType) {
        TelemetryNormalizationStrategy strategy = mock(TelemetryNormalizationStrategy.class);
        when(strategy.provider()).thenReturn(provider);
        when(strategy.eventType()).thenReturn(eventType);
        return strategy;
    }

    private static TelemetryRecord record(String provider, String eventType) {
        return new TelemetryRecord(USER, CONTEXT_EVENT, provider, eventType, "1",
            OffsetDateTime.parse("2026-07-11T06:30:00Z"), OffsetDateTime.parse("2026-07-11T07:00:00Z"),
            null, ZoneOffset.UTC);
    }
}
