package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("TelemetryConsumer — raw-first landing and DLQ discipline")
class TelemetryConsumerTest {

    private static final UUID DEFAULT_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private TelemetryIngestionService ingestionService;
    private TelemetryConsumer consumer;

    @BeforeEach
    void setUp() {
        ingestionService = mock(TelemetryIngestionService.class);
        consumer = new TelemetryConsumer(
            new ObjectMapper().findAndRegisterModules(), ingestionService, DEFAULT_USER);
    }

    @Test
    @DisplayName("a valid envelope is parsed and delegated to the ingestion service under the default user")
    void valid_envelope_is_delegated() {
        String body = """
            {
              "event_id": "11111111-1111-1111-1111-111111111111",
              "source_system": "LAMBDA_TELEMETRY",
              "provider": "APPLE_HEALTH",
              "event_type": "SLEEP_SESSION",
              "schema_version": "1",
              "occurred_at": "2026-07-10T22:00:00Z",
              "collected_at": "2026-07-11T07:00:00Z",
              "payload": { "start_time": "2026-07-10T22:00:00Z" }
            }
            """;

        consumer.onMessage(body);

        ArgumentCaptor<TelemetryEnvelope> captor = ArgumentCaptor.forClass(TelemetryEnvelope.class);
        verify(ingestionService).ingest(eq(DEFAULT_USER), captor.capture());
        TelemetryEnvelope envelope = captor.getValue();
        assertThat(envelope.eventId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(envelope.provider()).isEqualTo("APPLE_HEALTH");
        assertThat(envelope.eventType()).isEqualTo("SLEEP_SESSION");
        assertThat(envelope.payload().path("start_time").asText()).isEqualTo("2026-07-10T22:00:00Z");
    }

    @Test
    @DisplayName("a syntactically broken body cannot land raw → throws (redelivery → DLQ)")
    void malformed_json_throws_for_dlq() {
        assertThatThrownBy(() -> consumer.onMessage("{not json"))
            .isInstanceOf(TelemetryProcessingException.class);

        verifyNoInteractions(ingestionService);
    }

    @Test
    @DisplayName("a non-object JSON body (e.g. an array) is rejected for the DLQ")
    void non_object_json_throws_for_dlq() {
        assertThatThrownBy(() -> consumer.onMessage("[1, 2, 3]"))
            .isInstanceOf(TelemetryProcessingException.class);

        verifyNoInteractions(ingestionService);
    }

    @Test
    @DisplayName("an envelope with missing metadata still parses and lands raw (SKIPPED downstream, not DLQ)")
    void missing_metadata_still_lands() {
        // No provider/event_type/event_id — lenient parse yields nulls; still delegated (lands raw).
        consumer.onMessage("{ \"payload\": { \"foo\": 1 } }");

        ArgumentCaptor<TelemetryEnvelope> captor = ArgumentCaptor.forClass(TelemetryEnvelope.class);
        verify(ingestionService).ingest(eq(DEFAULT_USER), captor.capture());
        assertThat(captor.getValue().provider()).isNull();
        assertThat(captor.getValue().eventId()).isNull();
    }
}
