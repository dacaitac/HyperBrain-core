package com.hyperbrain.planner.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaBlockPlannedEvent;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.EmptyAgendaProposedEvent;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlanningWindow;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepWindow;
import com.hyperbrain.planner.domain.model.ValidatedAgenda;
import com.hyperbrain.planner.domain.model.ValidationContext;
import com.hyperbrain.planner.domain.port.out.AgendaMaterializationLedger;
import com.hyperbrain.planner.domain.port.out.AgendaProposer;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.AgendaInputHasher;
import com.hyperbrain.planner.domain.service.AgendaValidator;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.HumanizedAgendaFloor;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
 * replan-from-now run (lower bound = {@code max(wake, now)}).
 *
 * <p><b>Two entry points.</b> {@link #generate} is the synchronous verb the legacy path uses (morning
 * scheduler and the manual replan loop) — it always materializes. {@link #materializeIfNew} is the
 * idempotent verb the single-owner {@code AgendaJobConsumer} uses (HU-01c H2): it claims the
 * {@code (user, day, input_hash)} slot first and only materializes when the input is new, so an
 * at-least-once redelivery — or a replan from an unchanged state — is a no-op. Both share the same
 * {@code prepare → floor → finalize} core, so the plan they produce is identical; only the
 * idempotency guard differs.
 */
@Service
public class AgendaGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgendaGenerationService.class);

    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final PlannerStateRepository repository;
    private final SleepFrontierCalculator sleepFrontierCalculator;
    private final EnergyResolver energyResolver;
    private final PlanningWindowResolver planningWindowResolver;
    private final HumanizedAgendaFloor humanizedAgendaFloor;
    private final HumanizationSettings humanizationSettings;
    private final AgendaValidator agendaValidator;
    private final AgendaInputHasher inputHasher;
    private final AgendaMaterializationLedger materializationLedger;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AgendaProposer> agendaProposerProvider;

    AgendaGenerationService(
        PlannerStateRepository repository,
        SleepFrontierCalculator sleepFrontierCalculator,
        EnergyResolver energyResolver,
        PlanningWindowResolver planningWindowResolver,
        HumanizedAgendaFloor humanizedAgendaFloor,
        HumanizationSettings humanizationSettings,
        AgendaValidator agendaValidator,
        AgendaInputHasher inputHasher,
        AgendaMaterializationLedger materializationLedger,
        OutboxRepository outboxRepository,
        ObjectMapper objectMapper,
        ObjectProvider<AgendaProposer> agendaProposerProvider) {
        this.repository = repository;
        this.sleepFrontierCalculator = sleepFrontierCalculator;
        this.energyResolver = energyResolver;
        this.planningWindowResolver = planningWindowResolver;
        this.humanizedAgendaFloor = humanizedAgendaFloor;
        this.humanizationSettings = humanizationSettings;
        this.agendaValidator = agendaValidator;
        this.inputHasher = inputHasher;
        this.materializationLedger = materializationLedger;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.agendaProposerProvider = agendaProposerProvider;
    }

    /**
     * Generates the day's agenda for a user and persists the validated blocks.
     *
     * @param userId    the user whose day to plan; never null
     * @param targetDay the calendar day being planned; never null
     * @param zone      the user's timezone; never null
     * @param now       the reference instant; never null
     * @param fromNow   true for replan-from-now, false for a full-day run
     * @return the generated agenda (blocks, exclusions, paused tasks, energy criterion, degraded flag)
     */
    @Transactional
    public Agenda generate(UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime now,
                           boolean fromNow) {
        return generate(userId, targetDay, zone, now, fromNow, Set.of());
    }

    /**
     * Generates the day's agenda excluding tasks already placed on a previous day in the same
     * multi-day run. WIG lead measures are never excluded so they repeat daily as intended.
     *
     * @param excludedIds executable IDs already placed on an earlier day; never null
     */
    @Transactional
    Agenda generate(UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime now,
                    boolean fromNow, Set<UUID> excludedIds) {
        PreparedDay prepared = prepare(userId, targetDay, zone, now, fromNow, excludedIds);
        MaterializationInput input = proposeOrFloor(prepared);
        return finalizeMaterialization(userId, targetDay, zone, now, fromNow, prepared, input);
    }

    /**
     * Idempotent single-owner materialization (HU-01c H2): claims the {@code (user, day, input_hash)}
     * slot and materializes only when the input is new. Runs in one transaction so the claim, the
     * persisted blocks and the staged {@code AgendaBlockPlannedEvent} commit atomically — a rollback
     * releases the claim and lets SQS redeliver.
     *
     * @param userId      the user whose day to materialize; never null
     * @param targetDay   the calendar day being materialized; never null
     * @param zone        the user's timezone; never null
     * @param now         the reference instant (frozen at dispatch); never null
     * @param fromNow     true for replan-from-now, false for a full-day run
     * @param excludedIds executable IDs already placed on an earlier day of the same run; never null
     * @return the materialized agenda, or empty when the input was already materialized (deduplicated)
     */
    @Transactional
    public Optional<Agenda> materializeIfNew(UUID userId, LocalDate targetDay, ZoneId zone,
                                             OffsetDateTime now, boolean fromNow,
                                             Set<UUID> excludedIds) {
        PreparedDay prepared = prepare(userId, targetDay, zone, now, fromNow, excludedIds);
        String inputHash = inputHasher.hash(prepared.state(),
            hashableWalls(userId, targetDay, zone, prepared.occupied()));
        if (!materializationLedger.claim(userId, targetDay, inputHash)) {
            log.info("Agenda materialization skipped for user {} on {}: input {} already materialized",
                userId, targetDay, inputHash);
            return Optional.empty();
        }
        MaterializationInput input = proposeOrFloor(prepared);
        Agenda result =
            finalizeMaterialization(userId, targetDay, zone, now, fromNow, prepared, input);
        // Negative case (morning only): an empty day must tell the user it was planned for tomorrow.
        // Stage it in this same transaction as the claim (Transactional Outbox), so the notice is
        // exactly-once — atomic with the materialization, never a post-commit publish that a crash
        // could drop. A replan day (fromNow) never proposes the next day.
        if (!fromNow && result.blocks().isEmpty()) {
            stageEmptyAgendaProposal(userId, targetDay, zone, result.energyCriterion(), now);
        }
        return Optional.of(result);
    }

    /**
     * Replans the 48 h window from {@code occurredAt}: the start day is planned from now
     * ({@code fromNow = true}), each subsequent day as a full day, and non-WIG tasks placed on an
     * earlier day are excluded from later days so the same task is never double-booked across the
     * window (WIG lead measures are exempt — a daily recurring commitment). Each day's
     * delete-before-persist keeps repeats convergent.
     *
     * @param userId     the user whose window to replan; never null
     * @param occurredAt the instant the replan is anchored to; never null
     * @param zone       the user's timezone; never null
     */
    void replanAcrossWindow(UUID userId, OffsetDateTime occurredAt, ZoneId zone) {
        LocalDate startDay = occurredAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate lastDay = occurredAt.plusHours(48).atZoneSameInstant(zone).toLocalDate();
        Set<UUID> placed = new LinkedHashSet<>();
        for (LocalDate day = startDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            boolean fromNow = day.equals(startDay);
            Agenda agenda = generate(userId, day, zone, occurredAt, fromNow, placed);
            agenda.blocks().stream()
                .filter(block -> !block.wig())
                .map(block -> block.executableId())
                .forEach(placed::add);
        }
        log.info("Replan executed for user {} on {} days ({} → {}) from {}",
            userId, lastDay.toEpochDay() - startDay.toEpochDay() + 1, startDay, lastDay, occurredAt);
    }

    /**
     * Idempotent single-owner replan (HU-01c H2): claims the start day's {@code input_hash} and runs
     * the whole 48 h {@link #replanAcrossWindow} only when the input is new. A single claim guards the
     * whole run (not each day) so a redelivery of the same replan job is a clean no-op — re-running the
     * loop per day would rebuild the cross-day exclusions from an empty set and double-book. A genuine
     * replan whose start-day input moved (a later temporal frontier, a completed task) hashes anew and
     * runs. The claim and every day's blocks commit in one transaction.
     *
     * @param userId     the user whose window to replan; never null
     * @param occurredAt the instant the replan is anchored to; never null
     * @param zone       the user's timezone; never null
     * @return true when this call replanned, false when the replan was deduplicated
     */
    @Transactional
    public boolean materializeReplanIfNew(UUID userId, OffsetDateTime occurredAt, ZoneId zone) {
        LocalDate startDay = occurredAt.atZoneSameInstant(zone).toLocalDate();
        PreparedDay prepared = prepare(userId, startDay, zone, occurredAt, true, Set.of());
        String inputHash = inputHasher.hash(prepared.state(),
            hashableWalls(userId, startDay, zone, prepared.occupied()));
        if (!materializationLedger.claim(userId, startDay, inputHash)) {
            log.info("Replan skipped for user {} from {}: input {} already materialized",
                userId, startDay, inputHash);
            return false;
        }
        replanAcrossWindow(userId, occurredAt, zone);
        return true;
    }

    /**
     * Resolves the concrete-day planning state from the aggregates: the sleep frontier and energy, the
     * planning window, the ranked executables (filtered by the cross-day exclusions and the due-day
     * constraint), the WIG portfolio and the hard walls (existing blocks + meal anchors). Pure reads —
     * no persistence side effects — so it is safe to run before the idempotency claim.
     */
    private PreparedDay prepare(UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime now,
                                boolean fromNow, Set<UUID> excludedIds) {
        SleepWindow sleepWindow = sleepFrontierCalculator.computeWindow(
            repository.loadSleepFrontierInputs(userId, now));
        EnergyProfile energy = energyResolver.resolve(
            repository.loadLastNightSleepScore(userId, now));

        PlanningWindow window =
            planningWindowResolver.resolve(sleepWindow, targetDay, zone, now, fromNow);

        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<SchedulableExecutable> ranked = repository.loadRankedExecutables(userId, dayStart, dayEnd)
            .stream()
            .filter(e -> !excludedIds.contains(e.id()))
            .filter(e -> e.dueInstant() == null
                      || e.dueInstant().atZoneSameInstant(zone).toLocalDate().equals(targetDay))
            .toList();
        List<MciWig> wigPortfolio = repository.loadWigPortfolio(userId, now);
        List<OccupiedInterval> occupied = new ArrayList<>(repository.loadOccupiedIntervals(
            userId, window.frontierStart(), window.frontierEnd()));
        // H1 rule 2: fold the protected meal anchors into the walls so the humanized floor plans around
        // them (and the validator re-imposes them). Meal walls are never persisted nor written to Apple.
        occupied.addAll(mealWalls(targetDay, zone));

        // The fallback window (07:00–23:00) is always a valid planning frontier; the
        // observed flag only distinguishes learned vs. default, not usable vs. unusable.
        boolean dataComplete = true;

        PlannerDayState state = new PlannerDayState(
            window.lowerBound(), window.frontierEnd(), ranked, wigPortfolio, occupied, energy,
            dataComplete);
        return new PreparedDay(state, window, occupied);
    }

    /**
     * The propose-then-validate seam (ADR-019, HU-01c H3): runs the deterministic humanized floor, then
     * — only when the LLM tier is switched on ({@code app.cognitive.llm-propose.enabled}, surfaced as a
     * present {@link AgendaProposer} bean) — offers its blocks to the {@link AgendaProposer}. The
     * proposer returns an arranged agenda when the LLM cleared every bounded hard wall, or empty to
     * signal DEGRADED; either way the day materializes. With the flag off no proposer bean exists, so
     * this returns the floor unchanged (H1/H2, zero regression) and reads no titles.
     *
     * <p>The proposer only rearranges the floor's blocks, so the floor's exclusions/paused account and
     * energy criterion (its legibility record) are re-attached to the arranged blocks here.
     *
     * <p><b>Disposition authority (ADR-019 amendment, Daniel 2026-07-21).</b> The returned
     * {@link MaterializationInput} carries whether the day came from an <em>accepted</em> LLM proposal.
     * An accepted arrangement has already passed the bounded {@code ProposalWallGuard} (its only gate),
     * so the deterministic floor's {@code AgendaValidator} is bypassed for it — the LLM owns the
     * arrangement and the determinist never re-shuffles it. Every DEGRADED path (flag off, guard
     * rejection, LLM failure) materializes the humanized floor and still runs through the floor
     * validator, its usual safety net.
     */
    private MaterializationInput proposeOrFloor(PreparedDay prepared) {
        Agenda floorAgenda = humanizedAgendaFloor.generate(prepared.state());
        AgendaProposer proposer = agendaProposerProvider.getIfAvailable();
        if (proposer == null || floorAgenda.blocks().isEmpty()) {
            return new MaterializationInput(floorAgenda, false);
        }
        return proposer.propose(buildProposalContext(prepared, floorAgenda))
            .map(arranged -> new MaterializationInput(
                new Agenda(arranged.blocks(), floorAgenda.excluded(), floorAgenda.paused(),
                    floorAgenda.energyCriterion(), floorAgenda.degraded()),
                true))
            .orElse(new MaterializationInput(floorAgenda, false));
    }

    /**
     * Assembles the LLM-facing read model (#61) from the resolved day and the floor's block set: the
     * candidate blocks, the sleep frontier, the read-only AGENDA walls (ACTIVITY stays a movable
     * candidate, never a wall), the WIG ids, the F6 quota and the untrusted executable titles (the only
     * extra read, done here on the LLM path so the floor path stays untouched).
     */
    private AgendaProposalContext buildProposalContext(PreparedDay prepared, Agenda floorAgenda) {
        List<OccupiedInterval> agendaWalls = prepared.occupied().stream()
            .filter(OccupiedInterval::readOnlyAgenda)
            .toList();
        Set<UUID> wigIds = floorAgenda.blocks().stream()
            .filter(AgendaBlock::wig)
            .map(AgendaBlock::executableId)
            .collect(Collectors.toSet());
        Set<UUID> candidateIds = floorAgenda.blocks().stream()
            .map(AgendaBlock::executableId)
            .collect(Collectors.toSet());
        return new AgendaProposalContext(
            floorAgenda.blocks(),
            prepared.window().frontierStart(),
            prepared.window().frontierEnd(),
            agendaWalls,
            wigIds,
            prepared.state().energyProfile().highLoadQuota(),
            floorAgenda.energyCriterion(),
            repository.loadExecutableTitles(candidateIds));
    }

    /**
     * Re-imposes the hard walls, reconciles the day's blocks preserving identity (#15), stages the
     * write-back and returns the accepted agenda. Runs inside the caller's transaction.
     *
     * <p><b>Validator disposition (ADR-019 amendment).</b> When the day came from an accepted LLM
     * proposal ({@code input.fromAcceptedLlm()}), the floor's {@code AgendaValidator} is bypassed: the
     * bounded {@code ProposalWallGuard} ({sleep, AGENDA, WIG, structural}) already gated it and the LLM
     * owns the arrangement, so block spacing and F6 shaping materialize exactly as proposed. Every
     * DEGRADED/deterministic day still runs the floor validator as its non-negotiable safety net.
     */
    private Agenda finalizeMaterialization(UUID userId, LocalDate targetDay, ZoneId zone,
                                           OffsetDateTime now, boolean fromNow, PreparedDay prepared,
                                           MaterializationInput input) {
        Agenda agenda = input.agenda();
        PlannerDayState state = prepared.state();
        List<OccupiedInterval> occupied = prepared.occupied();
        PlanningWindow window = prepared.window();

        ValidatedAgenda validated;
        if (input.fromAcceptedLlm()) {
            validated = new ValidatedAgenda(agenda.blocks(), List.of());
        } else {
            ValidationContext validationContext = new ValidationContext(
                window.frontierStart(), window.frontierEnd(), occupied,
                state.energyProfile().highLoadQuota(), readOnlyAgendaIds(occupied));
            validated = agendaValidator.validate(agenda.blocks(), validationContext);
            if (!validated.isClean()) {
                log.warn("AgendaValidator rejected {} block(s) for user {}: {}",
                    validated.violations().size(), userId, validated.violations());
            }
        }

        // Identity-stable reconciliation (#15): a regeneration keeps a surviving block's id (so its
        // Apple EKEvent is UPDATED, not duplicated), inserts genuinely new blocks, and reports the
        // blocks that dropped out so their EKEvents are deleted — all in the same transaction as the
        // write-back staging, so the plan and its delivery are atomic.
        List<UUID> removedBlockIds =
            repository.reconcilePlannedBlocks(userId, targetDay, zone, validated.accepted());

        if (!validated.accepted().isEmpty() || !removedBlockIds.isEmpty()) {
            stageAgendaBlockDelivery(
                userId, targetDay, zone, agenda.energyCriterion(), removedBlockIds, now);
        }

        if (fromNow) {
            log.info("Replan completed: {} block(s) planned, {} removed (from {})",
                validated.accepted().size(), removedBlockIds.size(), window.lowerBound());
        }
        log.info("Planned {} block(s) for user {} ({} mode); removed {} prior, {} excluded, {} paused, degraded={}",
            validated.accepted().size(), userId, fromNow ? "replan" : "full-day",
            removedBlockIds.size(), agenda.excluded().size(), agenda.paused().size(), agenda.degraded());

        return new Agenda(validated.accepted(), agenda.excluded(), agenda.paused(),
            agenda.energyCriterion(), agenda.degraded());
    }

    /**
     * Stages the morning write-back in the same transaction as the persisted blocks (Transactional
     * Outbox): the day is either planned <em>and</em> queued for delivery to iOS, or neither. The
     * {@code SYSTEM} origin keeps the event eligible for outbound propagation (the drain suppresses
     * only the target's own origin), so the {@code AgendaBlockPropagator} routes it to Apple.
     */
    private void stageAgendaBlockDelivery(UUID userId, LocalDate targetDay, ZoneId zone,
                                          String energyCriterion, List<UUID> removedBlockIds,
                                          OffsetDateTime now) {
        AgendaBlockPlannedEvent event = new AgendaBlockPlannedEvent(
            userId, targetDay, zone.getId(), energyCriterion, removedBlockIds);
        outboxRepository.append(new OutboxEvent(
            UUID.randomUUID(),
            AgendaBlockPlannedEvent.AGGREGATE_TYPE,
            userId.toString(),
            AgendaBlockPlannedEvent.EVENT_TYPE,
            serialize(event),
            SOURCE_SYSTEM,
            now));
    }

    /**
     * Stages the empty-day next-day proposal in the same transaction as the idempotency claim
     * (Transactional Outbox). The {@code SYSTEM} origin keeps it eligible for outbound propagation, so
     * the {@code AgendaBlockPropagator} emits the notice reminder to Apple on drain.
     */
    private void stageEmptyAgendaProposal(UUID userId, LocalDate targetDay, ZoneId zone,
                                          String energyCriterion, OffsetDateTime now) {
        EmptyAgendaProposedEvent event = new EmptyAgendaProposedEvent(
            userId, targetDay, zone.getId(), energyCriterion, now);
        outboxRepository.append(new OutboxEvent(
            UUID.randomUUID(),
            EmptyAgendaProposedEvent.AGGREGATE_TYPE,
            userId.toString(),
            EmptyAgendaProposedEvent.EVENT_TYPE,
            serialize(event),
            SOURCE_SYSTEM,
            now));
    }

    private String serialize(EmptyAgendaProposedEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("user_id", event.userId().toString());
        node.put("target_day", event.targetDay().toString());
        node.put("zone_id", event.zoneId());
        node.put("energy_criterion", event.energyCriterion());
        node.put("reference_instant", event.referenceInstant().toString());
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize EmptyAgendaProposedEvent", ex);
        }
    }

    private String serialize(AgendaBlockPlannedEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("user_id", event.userId().toString());
        node.put("target_day", event.targetDay().toString());
        node.put("zone_id", event.zoneId());
        node.put("energy_criterion", event.energyCriterion());
        ArrayNode removed = node.putArray("removed_block_ids");
        event.removedBlockIds().forEach(id -> removed.add(id.toString()));
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AgendaBlockPlannedEvent", ex);
        }
    }

    /**
     * The protected meal-anchor walls for the target day (H1 rule 2), resolved from the humanized
     * settings against the user's zone. Windows outside the frontier are harmless — nothing is placed
     * there — so they are added unconditionally.
     */
    private List<OccupiedInterval> mealWalls(LocalDate targetDay, ZoneId zone) {
        return humanizationSettings.mealWindows().stream()
            .map(meal -> meal.toWall(targetDay, zone))
            .toList();
    }

    /**
     * The walls to feed the idempotency hash: the full occupancy minus the run's own regenerable
     * blocks (same-day {@code PLANNER}/{@code PLANNED}). A prior materialization persists those blocks,
     * and they are then re-read as occupied walls; folding them into the hash would make a redelivery
     * hash differently and re-materialize. Excluding them keeps the digest stable across redeliveries
     * while the floor still plans against the full occupancy. Only queried on the idempotent path, so
     * the synchronous {@link #generate} carries no extra read.
     */
    private List<OccupiedInterval> hashableWalls(UUID userId, LocalDate targetDay, ZoneId zone,
                                                 List<OccupiedInterval> occupied) {
        Set<String> regenerable = repository.loadPlannedBlocksForDay(userId, targetDay, zone).stream()
            .map(block -> wallKey(block.executableId(), block.start(), block.end()))
            .collect(Collectors.toSet());
        if (regenerable.isEmpty()) {
            return occupied;
        }
        return occupied.stream()
            .filter(wall -> !regenerable.contains(wallKey(wall.executableId(), wall.start(), wall.end())))
            .toList();
    }

    private static String wallKey(UUID executableId, OffsetDateTime start, OffsetDateTime end) {
        return executableId + "|" + start + "|" + end;
    }

    private static Set<UUID> readOnlyAgendaIds(List<OccupiedInterval> occupied) {
        return occupied.stream()
            .filter(OccupiedInterval::readOnlyAgenda)
            .map(OccupiedInterval::executableId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    }

    /** The resolved, side-effect-free inputs the floor and the finalize step share for one day. */
    private record PreparedDay(
        PlannerDayState state,
        PlanningWindow window,
        List<OccupiedInterval> occupied
    ) {
    }

    /**
     * The agenda to materialize plus its provenance: {@code fromAcceptedLlm} is true only for an LLM
     * proposal the {@code ProposalWallGuard} accepted, selecting the validator-bypass disposition in
     * {@link #finalizeMaterialization} (ADR-019 amendment). A DEGRADED or deterministic day carries
     * false, so it runs the floor's {@code AgendaValidator}.
     */
    private record MaterializationInput(Agenda agenda, boolean fromAcceptedLlm) {
    }
}
