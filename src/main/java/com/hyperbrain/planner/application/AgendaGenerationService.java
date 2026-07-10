package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlanningWindow;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepWindow;
import com.hyperbrain.planner.domain.model.ValidatedAgenda;
import com.hyperbrain.planner.domain.model.ValidationContext;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.AgendaGenerator;
import com.hyperbrain.planner.domain.service.AgendaValidator;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service that drives the deterministic agenda floor (#6a / HU-01a): it assembles the
 * concrete-day planning state from the aggregates, runs the deterministic generator, re-imposes the
 * hard walls with the {@link AgendaValidator}, and persists the accepted {@code PLANNED} blocks — all
 * in one transaction so the day is planned against a single consistent snapshot.
 *
 * <p>The same verb serves both modes via {@code fromNow}: a full-day run (lower bound = wake) and a
 * replan-from-now run (lower bound = {@code max(wake, now)}, for the future «calcular» button / HU-02).
 * The dispatch of this generation at {@code wake + 10min} and the write-back to iOS are separate
 * delivery slices — out of this scope.
 */
@Service
public class AgendaGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgendaGenerationService.class);

    private final PlannerStateRepository repository;
    private final SleepFrontierCalculator sleepFrontierCalculator;
    private final EnergyResolver energyResolver;
    private final PlanningWindowResolver planningWindowResolver;
    private final AgendaGenerator agendaGenerator;
    private final AgendaValidator agendaValidator;

    AgendaGenerationService(
        PlannerStateRepository repository,
        SleepFrontierCalculator sleepFrontierCalculator,
        EnergyResolver energyResolver,
        PlanningWindowResolver planningWindowResolver,
        AgendaGenerator agendaGenerator,
        AgendaValidator agendaValidator) {
        this.repository = repository;
        this.sleepFrontierCalculator = sleepFrontierCalculator;
        this.energyResolver = energyResolver;
        this.planningWindowResolver = planningWindowResolver;
        this.agendaGenerator = agendaGenerator;
        this.agendaValidator = agendaValidator;
    }

    /**
     * Generates the day's agenda for a user and persists the validated blocks.
     *
     * @param userId   the user whose day to plan; never null
     * @param targetDay the calendar day being planned; never null
     * @param zone     the user's timezone; never null
     * @param now      the reference instant; never null
     * @param fromNow  true for replan-from-now, false for a full-day run
     * @return the generated agenda (blocks, exclusions, paused tasks, energy criterion, degraded flag)
     */
    @Transactional
    public Agenda generate(UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime now,
                           boolean fromNow) {
        SleepWindow sleepWindow = sleepFrontierCalculator.computeWindow(
            repository.loadSleepFrontierInputs(userId, now));
        EnergyProfile energy = energyResolver.resolve(
            repository.loadLastNightSleepScore(userId, now));

        PlanningWindow window =
            planningWindowResolver.resolve(sleepWindow, targetDay, zone, now, fromNow);

        List<SchedulableExecutable> ranked = repository.loadRankedExecutables(userId);
        List<MciWig> wigPortfolio = repository.loadWigPortfolio(userId, now);
        List<OccupiedInterval> occupied = repository.loadOccupiedIntervals(
            userId, window.frontierStart(), window.frontierEnd());

        boolean dataComplete = sleepWindow.observed();

        PlannerDayState state = new PlannerDayState(
            window.lowerBound(), window.frontierEnd(), ranked, wigPortfolio, occupied, energy,
            dataComplete);

        Agenda agenda = agendaGenerator.generate(state);

        ValidationContext validationContext = new ValidationContext(
            window.frontierStart(), window.frontierEnd(), occupied, energy.highLoadQuota(),
            readOnlyAgendaIds(occupied));
        ValidatedAgenda validated = agendaValidator.validate(agenda.blocks(), validationContext);

        if (!validated.isClean()) {
            log.warn("AgendaValidator rejected {} block(s) for user {}: {}",
                validated.violations().size(), userId, validated.violations());
        }
        repository.persistPlannedBlocks(validated.accepted());

        log.info("Planned {} block(s) for user {} ({} mode); {} excluded, {} paused, degraded={}",
            validated.accepted().size(), userId, fromNow ? "replan" : "full-day",
            agenda.excluded().size(), agenda.paused().size(), agenda.degraded());

        return new Agenda(validated.accepted(), agenda.excluded(), agenda.paused(),
            agenda.energyCriterion(), agenda.degraded());
    }

    private static Set<UUID> readOnlyAgendaIds(List<OccupiedInterval> occupied) {
        return occupied.stream()
            .filter(OccupiedInterval::readOnlyAgenda)
            .map(OccupiedInterval::executableId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    }
}
