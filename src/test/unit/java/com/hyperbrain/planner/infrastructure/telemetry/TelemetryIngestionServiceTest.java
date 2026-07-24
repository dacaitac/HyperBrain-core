package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.NormalizationStatus;
import com.hyperbrain.planner.domain.model.RawTelemetryRow;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.port.out.RawTelemetryStore;
import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("TelemetryIngestionService — dedup, raw-first persist, normalize + status")
class TelemetryIngestionServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-11T09:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ProcessedMessageStore processedMessageStore;
    private RawTelemetryStore rawTelemetryStore;
    private TelemetryNormalizer normalizer;
    private PlannerStateRepository plannerStateRepository;
    private TelemetryIngestionService service;

    @BeforeEach
    void setUp() {
        processedMessageStore = mock(ProcessedMessageStore.class);
        rawTelemetryStore = mock(RawTelemetryStore.class);
        normalizer = mock(TelemetryNormalizer.class);
        plannerStateRepository = mock(PlannerStateRepository.class);
        service = new TelemetryIngestionService(processedMessageStore, rawTelemetryStore, normalizer,
            plannerStateRepository, objectMapper, CLOCK);
    }

    @Test
    @DisplayName("the raw envelope lands (PENDING) then is normalized and its status is written back")
    void happy_path_persists_raw_then_normalizes() {
        when(processedMessageStore.markProcessed(anyString(), anyString())).thenReturn(true);
        when(rawTelemetryStore.insertPending(any())).thenReturn(Optional.of(CONTEXT_EVENT));
        when(plannerStateRepository.loadUserZone(USER)).thenReturn(ZoneOffset.UTC);
        when(normalizer.normalize(any())).thenReturn(NormalizationStatus.NORMALIZED);

        service.ingest(USER, envelope(UUID.randomUUID(), "APPLE_HEALTH", "SLEEP_SESSION",
            "{ \"start_time\": \"2026-07-10T22:00:00Z\" }"));

        ArgumentCaptor<RawTelemetryRow> rowCaptor = ArgumentCaptor.forClass(RawTelemetryRow.class);
        verify(rawTelemetryStore).insertPending(rowCaptor.capture());
        RawTelemetryRow row = rowCaptor.getValue();
        assertThat(row.userId()).isEqualTo(USER);
        assertThat(row.provider()).isEqualTo("APPLE_HEALTH");
        assertThat(row.dedupKey()).startsWith("APPLE_HEALTH:SLEEP_SESSION:");
        assertThat(row.payloadJson()).contains("start_time");
        assertThat(row.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T22:00:00Z"));

        verify(normalizer).normalize(any());
        verify(rawTelemetryStore).markStatus(CONTEXT_EVENT, NormalizationStatus.NORMALIZED);
    }

    @Test
    @DisplayName("a duplicate event_id is skipped before any persistence")
    void duplicate_event_id_is_skipped() {
        when(processedMessageStore.markProcessed(anyString(), anyString())).thenReturn(false);

        service.ingest(USER, envelope(UUID.randomUUID(), "APPLE_HEALTH", "SLEEP_SESSION", "{}"));

        verifyNoInteractions(rawTelemetryStore);
        verifyNoInteractions(normalizer);
    }

    @Test
    @DisplayName("a semantic duplicate (dedup_key conflict) is acked without normalizing")
    void semantic_duplicate_skips_normalization() {
        when(processedMessageStore.markProcessed(anyString(), anyString())).thenReturn(true);
        when(rawTelemetryStore.insertPending(any())).thenReturn(Optional.empty());

        service.ingest(USER, envelope(UUID.randomUUID(), "APPLE_HEALTH", "SLEEP_SESSION", "{}"));

        verifyNoInteractions(normalizer);
        verify(rawTelemetryStore, never()).markStatus(any(), any());
    }

    @Test
    @DisplayName("an unknown provider still lands raw and is written back as SKIPPED (never DLQ)")
    void unknown_provider_lands_raw_and_is_skipped() {
        when(processedMessageStore.markProcessed(anyString(), anyString())).thenReturn(true);
        when(rawTelemetryStore.insertPending(any())).thenReturn(Optional.of(CONTEXT_EVENT));
        when(plannerStateRepository.loadUserZone(USER)).thenReturn(ZoneOffset.UTC);
        when(normalizer.normalize(any())).thenReturn(NormalizationStatus.SKIPPED);

        service.ingest(USER, envelope(UUID.randomUUID(), "RESCUETIME", "APP_ACTIVITY", "{}"));

        verify(rawTelemetryStore).insertPending(any());
        verify(rawTelemetryStore).markStatus(CONTEXT_EVENT, NormalizationStatus.SKIPPED);
    }

    @Test
    @DisplayName("an envelope without event_id skips the processed_message dedup but still lands raw")
    void null_event_id_still_lands_raw() {
        when(rawTelemetryStore.insertPending(any())).thenReturn(Optional.of(CONTEXT_EVENT));
        when(plannerStateRepository.loadUserZone(USER)).thenReturn(ZoneOffset.UTC);
        when(normalizer.normalize(any())).thenReturn(NormalizationStatus.NORMALIZED);

        service.ingest(USER, envelope(null, "APPLE_HEALTH", "SLEEP_SESSION", "{}"));

        verifyNoInteractions(processedMessageStore);
        verify(rawTelemetryStore).insertPending(any());
        verify(rawTelemetryStore).markStatus(CONTEXT_EVENT, NormalizationStatus.NORMALIZED);
    }

    private TelemetryEnvelope envelope(UUID eventId, String provider, String eventType, String payloadJson) {
        return new TelemetryEnvelope(eventId, "LAMBDA_TELEMETRY", provider, eventType, "1",
            OffsetDateTime.parse("2026-07-10T22:00:00Z"), OffsetDateTime.parse("2026-07-11T07:00:00Z"),
            readTree(payloadJson));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
