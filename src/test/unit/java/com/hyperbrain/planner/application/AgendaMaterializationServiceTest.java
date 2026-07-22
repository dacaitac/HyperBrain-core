package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.DailyAgendaRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgendaMaterializationService — single owner of materialization (HU-01c H2)")
class AgendaMaterializationServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime T = OffsetDateTime.of(2026, 7, 10, 6, 41, 0, 0, ZoneOffset.UTC);
    // The service resolves ZoneId.of(job.zoneId()); "UTC" yields a ZoneRegion, not ZoneOffset.UTC ("Z").
    private static final ZoneId UTC = ZoneId.of("UTC");

    private AgendaGenerationService generationService;
    private AgendaMaterializationService service;

    @BeforeEach
    void setUp() {
        generationService = mock(AgendaGenerationService.class);
        service = new AgendaMaterializationService(generationService);
    }

    @Test
    @DisplayName("a morning job materializes the requested day (the negative case is staged in-tx)")
    void morning_materializes_the_day() {
        when(generationService.materializeIfNew(USER, DAY, UTC, T, false, Set.of()))
            .thenReturn(Optional.of(agenda()));

        service.materialize(morningJob());

        verify(generationService).materializeIfNew(USER, DAY, UTC, T, false, Set.of());
    }

    @Test
    @DisplayName("a deduplicated morning redelivery materializes nothing (claim returns empty)")
    void morning_deduplicated_is_silent() {
        when(generationService.materializeIfNew(USER, DAY, UTC, T, false, Set.of()))
            .thenReturn(Optional.empty());

        service.materialize(morningJob());

        verify(generationService).materializeIfNew(USER, DAY, UTC, T, false, Set.of());
    }

    @Test
    @DisplayName("a replan job delegates to the idempotent 48 h replan")
    void replan_delegates_to_replan_window() {
        when(generationService.materializeReplanIfNew(USER, T, UTC)).thenReturn(true);

        service.materialize(replanJob());

        verify(generationService).materializeReplanIfNew(eq(USER), eq(T), eq(UTC));
    }

    @Test
    @DisplayName("a deduplicated replan redelivery is a no-op")
    void replan_deduplicated_is_noop() {
        when(generationService.materializeReplanIfNew(USER, T, UTC)).thenReturn(false);

        service.materialize(replanJob());

        verify(generationService).materializeReplanIfNew(USER, T, UTC);
    }

    private static DailyAgendaRequestedEvent morningJob() {
        return new DailyAgendaRequestedEvent(USER, DAY, "UTC", T, false);
    }

    private static DailyAgendaRequestedEvent replanJob() {
        return new DailyAgendaRequestedEvent(USER, DAY, "UTC", T, true);
    }

    private static Agenda agenda() {
        return new Agenda(List.of(), List.of(), List.of(), "NEUTRAL", false);
    }
}
