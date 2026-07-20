package com.hyperbrain.prioritizer.application;

import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OnIngestionPriorityReflector (#66a central post-upsert reflection)")
class OnIngestionPriorityReflectorTest {

    private static final UUID EXECUTABLE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private PrioritizerService prioritizerService;
    private OutboxRepository outboxRepo;
    private OnIngestionPriorityReflector reflector;

    @BeforeEach
    void setUp() {
        prioritizerService = mock(PrioritizerService.class);
        outboxRepo = mock(OutboxRepository.class);
        reflector = new OnIngestionPriorityReflector(prioritizerService, outboxRepo);
    }

    @Test
    @DisplayName("always rescores the executable, whatever the origin")
    void always_rescores() {
        when(prioritizerService.rescore(EXECUTABLE)).thenReturn(RescoreResult.noSignal());

        reflector.reflect(EXECUTABLE, ExternalSystem.APPLE);

        verify(prioritizerService).rescore(EXECUTABLE);
    }

    @Test
    @DisplayName("NOTION origin + score moved: stages one SYSTEM ExecutableUpdatedEvent; returns true")
    void notion_moved_stages_system_event() {
        when(prioritizerService.rescore(EXECUTABLE))
            .thenReturn(RescoreResult.scored(new PriorityScore(EXECUTABLE, 0.7, 3.0, 0.5), true));

        boolean staged = reflector.reflect(EXECUTABLE, ExternalSystem.NOTION);

        assertThat(staged).isTrue();
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        OutboxEvent event = outbox.getValue();
        assertThat(event.sourceSystem()).isEqualTo("SYSTEM");
        assertThat(event.eventType()).isEqualTo("ExecutableUpdatedEvent");
        assertThat(event.aggregateType()).isEqualTo("CORE_EXECUTABLE");
        assertThat(event.aggregateId()).isEqualTo(EXECUTABLE.toString());
    }

    @Test
    @DisplayName("NOTION origin + score did NOT move: still stages a SYSTEM event so the canonical state reaches Notion past loop protection; returns true")
    void notion_unmoved_still_stages_system_event() {
        when(prioritizerService.rescore(EXECUTABLE))
            .thenReturn(RescoreResult.scored(new PriorityScore(EXECUTABLE, 0.7, 3.0, 0.5), false));

        boolean staged = reflector.reflect(EXECUTABLE, ExternalSystem.NOTION);

        assertThat(staged).isTrue();
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        assertThat(outbox.getValue().sourceSystem()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("APPLE origin + score moved: stages no extra event — the APPLE event already reflects the score; returns false")
    void apple_moved_stages_no_event() {
        when(prioritizerService.rescore(EXECUTABLE))
            .thenReturn(RescoreResult.scored(new PriorityScore(EXECUTABLE, 0.7, 3.0, 0.5), true));

        boolean staged = reflector.reflect(EXECUTABLE, ExternalSystem.APPLE);

        assertThat(staged).isFalse();
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("APPLE origin + score did not move: stages no event; returns false")
    void apple_unmoved_stages_no_event() {
        when(prioritizerService.rescore(EXECUTABLE))
            .thenReturn(RescoreResult.scored(new PriorityScore(EXECUTABLE, 0.7, 3.0, 0.5), false));

        boolean staged = reflector.reflect(EXECUTABLE, ExternalSystem.APPLE);

        assertThat(staged).isFalse();
        verify(outboxRepo, never()).append(any());
    }
}
