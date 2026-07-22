package com.hyperbrain.planner.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DailyAgendaRequestedEvent;
import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MorningTriggerState;
import com.hyperbrain.planner.domain.port.out.MorningTriggerStore;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("AgendaJobEmitter — Transactional Outbox enqueue to ia-jobs (HU-01c H2)")
class AgendaJobEmitterTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final OffsetDateTime T = OffsetDateTime.of(2026, 7, 10, 6, 41, 0, 0, ZoneOffset.UTC);

    private OutboxRepository outboxRepository;
    private MorningTriggerStore morningTriggerStore;
    private AgendaJobEmitter emitter;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        morningTriggerStore = mock(MorningTriggerStore.class);
        emitter = new AgendaJobEmitter(outboxRepository, morningTriggerStore, new ObjectMapper());
    }

    @Test
    @DisplayName("morning job: saves the once-per-day guard and appends an IA_JOB event (from_now=false)")
    void morning_job_saves_guard_and_appends() throws Exception {
        MorningTriggerState state = new MorningTriggerState(LocalTimeOfDay.of(6, 40), DAY);

        emitter.emitMorningJob(USER, DAY, ZONE, T, state);

        verify(morningTriggerStore).save(USER, state);
        OutboxEvent event = captureAppended();
        assertThat(event.aggregateType()).isEqualTo(DailyAgendaRequestedEvent.AGGREGATE_TYPE);
        assertThat(event.eventType()).isEqualTo(DailyAgendaRequestedEvent.EVENT_TYPE);
        assertThat(event.aggregateId()).isEqualTo(USER.toString());
        assertThat(event.occurredAt()).isEqualTo(T);

        JsonNode payload = new ObjectMapper().readTree(event.payload());
        assertThat(payload.path("user_id").asText()).isEqualTo(USER.toString());
        assertThat(payload.path("agenda_date").asText()).isEqualTo(DAY.toString());
        assertThat(payload.path("zone_id").asText()).isEqualTo("America/Bogota");
        assertThat(payload.path("reference_instant").asText()).isEqualTo(T.toString());
        assertThat(payload.path("from_now").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("replan job: appends an IA_JOB event (from_now=true) without touching the morning guard")
    void replan_job_appends_without_guard() throws Exception {
        emitter.emitReplanJob(USER, DAY, ZONE, T);

        verifyNoInteractions(morningTriggerStore);
        OutboxEvent event = captureAppended();
        assertThat(event.aggregateType()).isEqualTo(DailyAgendaRequestedEvent.AGGREGATE_TYPE);
        JsonNode payload = new ObjectMapper().readTree(event.payload());
        assertThat(payload.path("from_now").asBoolean()).isTrue();
        assertThat(payload.path("agenda_date").asText()).isEqualTo(DAY.toString());
    }

    private OutboxEvent captureAppended() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).append(captor.capture());
        return captor.getValue();
    }
}
