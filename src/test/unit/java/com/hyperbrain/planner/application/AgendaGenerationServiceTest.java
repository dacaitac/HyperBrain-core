package com.hyperbrain.planner.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.application.OverdueSweepService;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyTier;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlanningWindow;
import com.hyperbrain.planner.domain.model.SleepWindow;
import com.hyperbrain.planner.domain.model.ValidatedAgenda;
import com.hyperbrain.planner.domain.port.out.AgendaMaterializationLedger;
import com.hyperbrain.planner.domain.port.out.AgendaProposer;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.AgendaInputHasher;
import com.hyperbrain.planner.domain.service.AgendaValidator;
import com.hyperbrain.planner.domain.service.DayWindowResolver;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.HumanizedAgendaFloor;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.RetimingBandResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgendaGenerationService}'s own coordination — what neither the domain unit
 * tests nor {@code AgendaGenerationServiceIT} can reach:
 * <ul>
 *   <li>the replan-window loop that moved here from {@code UserCommandService} (HU-01c H2), asserted
 *       with a spy over the single-day {@code generateDay} so the day-spanning and {@code fromNow}
 *       semantics stand alone;</li>
 *   <li>the assembly of the LLM-facing read model, which is private and only observable through the
 *       {@link AgendaProposer} it is handed to.</li>
 * </ul>
 */
@DisplayName("AgendaGenerationService — replan loop and the read model it hands the LLM")
class AgendaGenerationServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);

    private AgendaGenerationService service;
    private OverdueSweepService sweepService;
    private PlannerStateRepository repository;
    private SleepFrontierCalculator sleepFrontierCalculator;
    private EnergyResolver energyResolver;
    private PlanningWindowResolver planningWindowResolver;
    private HumanizedAgendaFloor humanizedAgendaFloor;
    private HumanizationSettings humanizationSettings;
    private AgendaValidator agendaValidator;
    private ObjectProvider<AgendaProposer> proposerProvider;

    @BeforeEach
    void setUp() {
        sweepService = mock(OverdueSweepService.class);
        repository = mock(PlannerStateRepository.class);
        sleepFrontierCalculator = mock(SleepFrontierCalculator.class);
        energyResolver = mock(EnergyResolver.class);
        planningWindowResolver = mock(PlanningWindowResolver.class);
        humanizedAgendaFloor = mock(HumanizedAgendaFloor.class);
        humanizationSettings = mock(HumanizationSettings.class);
        agendaValidator = mock(AgendaValidator.class);
        proposerProvider = emptyProvider();
        service = spy(build(false));
    }

    private AgendaGenerationService build(boolean sweepOnReplanEnabled) {
        return new AgendaGenerationService(
            repository,
            mock(PlannerBlockMaterializer.class),
            new DayWindowResolver(com.hyperbrain.planner.domain.model.DayTemplate.DEFAULT),
            new RetimingBandResolver(com.hyperbrain.planner.domain.model.DayTemplate.DEFAULT,
                HumanizationSettings.DEFAULT),
            mock(MovedCommitmentRescuer.class),
            sleepFrontierCalculator,
            energyResolver,
            planningWindowResolver,
            humanizedAgendaFloor,
            humanizationSettings,
            agendaValidator,
            mock(AgendaInputHasher.class),
            mock(AgendaMaterializationLedger.class),
            mock(OutboxRepository.class),
            new ObjectMapper(),
            proposerProvider,
            sweepService,
            sweepOnReplanEnabled);
    }

    /** An {@link ObjectProvider} with no {@link AgendaProposer} — the LLM tier is off (H1/H2 path). */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgendaProposer> emptyProvider() {
        ObjectProvider<AgendaProposer> provider = mock(ObjectProvider.class);
        return provider;
    }

    @Test
    @DisplayName("covers 48 h: fromNow=true on startDay, fromNow=false on each subsequent day")
    void replan_spans_window_with_from_now_on_start_day() {
        // occurredAt = 2026-07-11T02:00Z = 2026-07-10 21:00 Bogota → startDay = July 10
        // horizon    = 2026-07-13T02:00Z = 2026-07-12 21:00 Bogota → lastDay  = July 12 → 3 days
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        Agenda empty = new Agenda(List.of(), List.of(), List.of(), "NEUTRAL", false);
        AgendaGenerationService.DayResult emptyResult =
            new AgendaGenerationService.DayResult(empty, List.of());
        doReturn(emptyResult).when(service)
            .generateDay(eq(USER), any(LocalDate.class), eq(BOGOTA), eq(occurredAt), anyBoolean(), any());

        service.replanAcrossWindow(USER, occurredAt, BOGOTA);

        verify(service).generateDay(
            eq(USER), eq(LocalDate.of(2026, 7, 10)), eq(BOGOTA), eq(occurredAt), eq(true), any(Set.class));
        verify(service, times(3)).generateDay(
            eq(USER), any(LocalDate.class), eq(BOGOTA), eq(occurredAt), anyBoolean(), any(Set.class));
    }

    @Test
    @DisplayName("with the sweep on, a replan retires what is behind its trigger BEFORE it plans anything")
    void the_replan_sweeps_before_it_generates() {
        AgendaGenerationService sweeping = spy(build(true));
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        Agenda empty = new Agenda(List.of(), List.of(), List.of(), "NEUTRAL", false);
        doReturn(new AgendaGenerationService.DayResult(empty, List.of())).when(sweeping)
            .generateDay(any(), any(), any(), any(), anyBoolean(), any());

        sweeping.replanAcrossWindow(USER, occurredAt, BOGOTA);

        // Order is the whole point: a day planned over the old bag is the defect this fixes.
        InOrder order = inOrder(sweepService, sweeping);
        order.verify(sweepService).sweep(USER, occurredAt, BOGOTA);
        order.verify(sweeping, times(3)).generateDay(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("the switch is off by default: a replan sweeps nothing until Daniel turns it on")
    void the_sweep_is_off_by_default() {
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        Agenda empty = new Agenda(List.of(), List.of(), List.of(), "NEUTRAL", false);
        doReturn(new AgendaGenerationService.DayResult(empty, List.of())).when(service)
            .generateDay(any(), any(), any(), any(), anyBoolean(), any());

        service.replanAcrossWindow(USER, occurredAt, BOGOTA);

        verifyNoInteractions(sweepService);
    }

    @Test
    @DisplayName("a standing activity reaches the model as an occupied wall; a meal anchor does not")
    void a_commitment_wall_reaches_the_model() {
        // Given: a day whose occupancy is an activity («Desayunar» at 10:30) and a meal anchor. Both are
        // walls of the run, but only one has an owner — and the read model's filter is exactly that
        // distinction, so a commitment reaching the model must be verified, not assumed.
        UUID activity = UUID.fromString("11111111-0000-0000-0000-00000000000a");
        givenAFullDay(List.of(
            new OccupiedInterval(activity, at(10, 30), at(11, 30), false),
            new OccupiedInterval(null, at(7, 0), at(7, 30), false)));
        AgendaBlock block = aBlockAt(at(8, 30), at(10, 30));
        when(humanizedAgendaFloor.generate(any()))
            .thenReturn(new Agenda(List.of(block), List.of(), List.of(), "NEUTRAL", false));
        when(agendaValidator.validate(any(), any()))
            .thenReturn(new ValidatedAgenda(List.of(block), List.of()));
        AgendaProposer proposer = mock(AgendaProposer.class);
        when(proposerProvider.getIfAvailable()).thenReturn(proposer);

        // When
        service.generateDay(USER, DAY, ZoneOffset.UTC, at(12, 0), false, Set.of());

        // Then
        ArgumentCaptor<AgendaProposalContext> context =
            ArgumentCaptor.forClass(AgendaProposalContext.class);
        verify(proposer).propose(context.capture());
        assertThat(context.getValue().occupiedWalls())
            .extracting(OccupiedInterval::executableId)
            .containsExactly(activity);
    }

    /**
     * A plannable full day (07:00–23:00, nothing already planned) carrying the given walls — the least
     * stubbing that gets a run as far as the proposer.
     */
    private void givenAFullDay(List<OccupiedInterval> walls) {
        when(sleepFrontierCalculator.computeWindow(any()))
            .thenReturn(new SleepWindow(LocalTimeOfDay.of(7, 0), LocalTimeOfDay.of(23, 0), true));
        when(energyResolver.resolve(any()))
            .thenReturn(new EnergyProfile(EnergyTier.NEUTRAL, 2, "NEUTRAL"));
        when(planningWindowResolver.resolve(any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new PlanningWindow(at(7, 0), at(23, 0), at(7, 0)));
        when(repository.loadOccupiedIntervals(eq(USER), any(), any())).thenReturn(walls);
    }

    private static AgendaBlock aBlockAt(OffsetDateTime start, OffsetDateTime end) {
        return new AgendaBlock(UUID.fromString("22222222-0000-0000-0000-00000000000b"), start, end,
            false, false, "laid", List.of(), null, "GOAL_MORNING");
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, java.time.LocalTime.of(hour, minute), ZoneOffset.UTC);
    }
}
