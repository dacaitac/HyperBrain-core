package com.hyperbrain.planner.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.port.out.AgendaMaterializationLedger;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.AgendaInputHasher;
import com.hyperbrain.planner.domain.service.AgendaValidator;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.HumanizedAgendaFloor;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for the replan-window loop that moved from {@code UserCommandService} into
 * {@link AgendaGenerationService#replanAcrossWindow} (HU-01c H2). A spy stubs the single-day
 * {@code generate} so the loop's day-spanning and {@code fromNow} semantics are asserted in
 * isolation; the day generation itself is covered by {@code AgendaGenerationServiceIT}.
 */
@DisplayName("AgendaGenerationService.replanAcrossWindow — 48 h replan loop (HU-01c H2)")
class AgendaGenerationServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private AgendaGenerationService service;

    @BeforeEach
    void setUp() {
        service = spy(new AgendaGenerationService(
            mock(PlannerStateRepository.class),
            mock(SleepFrontierCalculator.class),
            mock(EnergyResolver.class),
            mock(PlanningWindowResolver.class),
            mock(HumanizedAgendaFloor.class),
            mock(HumanizationSettings.class),
            mock(AgendaValidator.class),
            mock(AgendaInputHasher.class),
            mock(AgendaMaterializationLedger.class),
            mock(OutboxRepository.class),
            new ObjectMapper()));
    }

    @Test
    @DisplayName("covers 48 h: fromNow=true on startDay, fromNow=false on each subsequent day")
    void replan_spans_window_with_from_now_on_start_day() {
        // occurredAt = 2026-07-11T02:00Z = 2026-07-10 21:00 Bogota → startDay = July 10
        // horizon    = 2026-07-13T02:00Z = 2026-07-12 21:00 Bogota → lastDay  = July 12 → 3 days
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        Agenda empty = new Agenda(List.of(), List.of(), List.of(), "NEUTRAL", false);
        doReturn(empty).when(service)
            .generate(eq(USER), any(LocalDate.class), eq(BOGOTA), eq(occurredAt), anyBoolean(), any());

        service.replanAcrossWindow(USER, occurredAt, BOGOTA);

        verify(service).generate(
            eq(USER), eq(LocalDate.of(2026, 7, 10)), eq(BOGOTA), eq(occurredAt), eq(true), any(Set.class));
        verify(service, times(3)).generate(
            eq(USER), any(LocalDate.class), eq(BOGOTA), eq(occurredAt), anyBoolean(), any(Set.class));
    }
}
