package com.hyperbrain.planner.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.core.application.OverdueSweepService;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.EmptyAgendaProposedEvent;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannerBlockIdentity;
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
import com.hyperbrain.planner.domain.service.DayWindowResolver;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.HumanizedAgendaFloor;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
    private final PlannerBlockMaterializer blockMaterializer;
    private final DayWindowResolver dayWindowResolver;
    private final MovedCommitmentRescuer commitmentRescuer;
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
    private final OverdueSweepService overdueSweepService;
    private final boolean sweepOnReplanEnabled;

    AgendaGenerationService(
        PlannerStateRepository repository,
        PlannerBlockMaterializer blockMaterializer,
        DayWindowResolver dayWindowResolver,
        MovedCommitmentRescuer commitmentRescuer,
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
        ObjectProvider<AgendaProposer> agendaProposerProvider,
        OverdueSweepService overdueSweepService,
        @Value("${app.core.overdue-sweep.enabled:false}") boolean sweepOnReplanEnabled) {
        this.repository = repository;
        this.blockMaterializer = blockMaterializer;
        this.dayWindowResolver = dayWindowResolver;
        this.commitmentRescuer = commitmentRescuer;
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
        this.overdueSweepService = overdueSweepService;
        this.sweepOnReplanEnabled = sweepOnReplanEnabled;
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
        return generateDay(userId, targetDay, zone, now, fromNow, excludedIds).agenda();
    }

    /**
     * Materializes one day and reports the executables the <b>deterministic floor</b> scheduled for it
     * (excluding the WIG). The multi-day replan keys its cross-day exclusion on this floor set — not on
     * the possibly LLM-arranged result — so the day distribution stays the floor's coherent one (today
     * filled to the cap, only the genuine overflow carried to the next day): an LLM drop humanizes the
     * day but never blindly dumps the day's blocks onto tomorrow.
     */
    DayResult generateDay(UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime now,
                          boolean fromNow, Set<UUID> excludedIds) {
        PreparedDay prepared = prepare(userId, targetDay, zone, now, fromNow, excludedIds);
        if (!prepared.plannable()) {
            log.debug("No forward window to plan for user {} on {} (replan at/after bedtime); "
                + "empty day, moving on", userId, targetDay);
            return new DayResult(emptyAgenda(prepared.energyCriterion()), List.of());
        }
        MaterializationInput input = proposeOrFloor(prepared);
        Agenda materialized =
            finalizeMaterialization(userId, targetDay, zone, now, fromNow, prepared, input);
        List<UUID> floorScheduled = input.floorAgenda().blocks().stream()
            .filter(block -> !block.wig())
            .map(AgendaBlock::executableId)
            .toList();
        return new DayResult(materialized, floorScheduled);
    }

    /**
     * Idempotent single-owner materialization (HU-01c H2): claims the {@code (user, day, input_hash)}
     * slot and materializes only when the input is new. Runs in one transaction so the claim, the
     * persisted blocks and the outbox rows announcing them commit atomically — a rollback releases the
     * claim and lets SQS redeliver.
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
        if (!prepared.plannable()) {
            // No forward window (a replan at/after bedtime landing on this single day): a graceful empty
            // day, never a thrown zero-width window. A multi-day replan reaches the next day separately.
            return Optional.of(emptyAgenda(prepared.energyCriterion()));
        }
        // prepared.occupied() already excludes the run's own regenerable blocks (see prepare), so it is
        // the stable wall set the idempotency digest needs.
        String inputHash = inputHasher.hash(prepared.state(), prepared.occupied());
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
     * <p><b>The sweep runs first, and the order is the point</b> (Daniel, 2026-08-07). Every replan
     * retires what is behind its own trigger hour <em>before</em> a single window is laid, so the day
     * is planned over a bag that is already clean and this morning's leftovers are back among today's
     * candidates. Sweeping afterwards would leave the run planning against the state the button was
     * meant to correct. It sits here rather than in the command handler because both replan roads —
     * the synchronous one and the {@code ia-jobs} one — pass through this method, and it shares the
     * caller's transaction, so the retirement and the plan it enables commit together.
     *
     * @param userId     the user whose window to replan; never null
     * @param occurredAt the instant the replan is anchored to; never null
     * @param zone       the user's timezone; never null
     */
    void replanAcrossWindow(UUID userId, OffsetDateTime occurredAt, ZoneId zone) {
        if (sweepOnReplanEnabled) {
            overdueSweepService.sweep(userId, occurredAt, zone);
        }
        LocalDate startDay = occurredAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate lastDay = occurredAt.plusHours(48).atZoneSameInstant(zone).toLocalDate();
        Set<UUID> placed = new LinkedHashSet<>();
        for (LocalDate day = startDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            boolean fromNow = day.equals(startDay);
            DayResult result = generateDay(userId, day, zone, occurredAt, fromNow, placed);
            // Exclude from later days what the FLOOR scheduled today (not what the LLM kept), so the
            // cross-day distribution follows the floor's coherent spreading and an LLM drop never
            // cascades the whole day onto tomorrow.
            placed.addAll(result.floorScheduled());
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
        // A late replan whose start day has no forward window still replans the rest of the horizon
        // (tomorrow onward), so it must run — but its start-day state is absent, so the idempotency key
        // falls back to the replan anchor (user + start day + frozen instant), which dedupes redeliveries
        // just the same while a genuinely later button press hashes anew.
        String inputHash = prepared.plannable()
            ? inputHasher.hash(prepared.state(), prepared.occupied())
            : inputHasher.replanAnchorHash(userId, startDay, occurredAt);
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

        // A replan issued at or after bedtime leaves no forward window for this day (lowerBound clamps
        // up to the frontier end, so the window has zero width). Rather than let PlannerDayState throw on
        // a zero-width window — which would fail the SQS listener and loop the message to the DLQ — yield
        // an unplannable prepared day. The caller produces an empty agenda for this day; a multi-day
        // replan simply moves on to the next day, whose window is valid (planning tomorrow, as intended).
        if (!window.lowerBound().isBefore(window.frontierEnd())) {
            return PreparedDay.unplannable(window, energy.criterion());
        }

        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        // Admission (ADR-040 D3): the day takes what is dated FOR it, plus the dateless bag it draws
        // from. Nothing else — an overdue item is not dragged forward here, because the day-close sweep
        // (D4) is what re-dates it; pulling the past into today as well would double-count it and undo
        // the sweep's work. A future-dated item waits for its own day.
        List<SchedulableExecutable> ranked = repository.loadRankedExecutables(userId, dayStart, dayEnd, window.lowerBound())
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

        // The floor plans against occupancy MINUS the run's OWN regenerable blocks (same-day
        // PLANNER/PLANNED). Those blocks are exactly the ones this run re-creates: treating them as walls
        // would leave a replan of an already-materialized day (e.g. after the morning dispatch) no room
        // to re-place its own tasks — it would exclude them, and reconciliation would then delete their
        // blocks, pushing the tasks onto future days. Ignoring them lets the generator re-place the tasks
        // fresh from now, and reconciliation converges them by stable id. Every other wall stands: the
        // read-only AGENDA windows and the TIME_BLOCK executables that hold time (USER blocks, and the
        // ones already closed as DONE / FAILED). See withoutRegenerable for why the subtraction is inert
        // until the two sides read the same block model.
        // The plan that already exists for this day, bounded to what this run may still touch: a block
        // that already started is not regenerable, so it is neither re-placed nor un-walled (D8).
        List<PlannerBlockIdentity.PersistedBlock> existingPlan =
            repository.loadRegenerableBlocks(userId, targetDay, zone, window.lowerBound());
        List<OccupiedInterval> planningWalls = withoutRegenerable(existingPlan, occupied);

        // The fallback window (07:00–23:00) is always a valid planning frontier; the
        // observed flag only distinguishes learned vs. default, not usable vs. unusable.
        boolean dataComplete = true;

        // The day's windows are laid before anything is put in them (ADR-040): against the template,
        // displaced by the REAL wake instant, and clipped by the same walls the generator plans around.
        // The floor decides what goes in each window; it never decides where a window sits.
        List<DayWindow> windows = dayWindowResolver.resolve(
            targetDay, zone, window.frontierStart(), window.lowerBound(), window.frontierEnd(),
            planningWalls);

        PlannerDayState state = new PlannerDayState(
            window.lowerBound(), window.frontierEnd(), windows, membershipBySlot(existingPlan), ranked,
            wigPortfolio, planningWalls, energy, dataComplete);
        return new PreparedDay(state, window, planningWalls, energy.criterion());
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
            return new MaterializationInput(floorAgenda, false, floorAgenda);
        }
        return proposer.propose(buildProposalContext(prepared, floorAgenda))
            .map(arranged -> new MaterializationInput(
                new Agenda(arranged.blocks(), floorAgenda.excluded(), floorAgenda.paused(),
                    floorAgenda.energyCriterion(), floorAgenda.degraded()),
                true, floorAgenda))
            .orElse(new MaterializationInput(floorAgenda, false, floorAgenda));
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
     * The empty agenda for a day with no plannable forward window (a replan at/after bedtime). It
     * carries no blocks and no exclusions but keeps the readable energy criterion, and it deliberately
     * skips reconciliation so an end-of-day replan never wipes the day's existing plan — it simply plans
     * nothing new for today and moves on to the next day.
     */
    private static Agenda emptyAgenda(String energyCriterion) {
        return new Agenda(List.of(), List.of(), List.of(), energyCriterion, false);
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

        // Identity, withdrawal and containment all live in the materializer, where ADR-040 D10's
        // order-of-operations invariant is readable in one place.
        // The day's readable criterion rides on each block's own note now: the private delivery
        // channel that used to carry it in an event payload is gone, and a block is an executable
        // whose description its mirrors already show.
        List<UUID> removedBlockIds = blockMaterializer.materialize(
            userId, targetDay, zone, validated.accepted(), window.lowerBound(),
            agenda.energyCriterion());

        // ADR-040 D9, middle tier. Only on a replan, and only for a commitment an interruption has
        // already run over: activities and study sessions keep their day and lose only their hour.
        // They are never members of a window — they carry a calendar window of their own — so this is
        // the single place the planner writes onto something the user created in his own calendar.
        if (fromNow) {
            commitmentRescuer.rescue(userId, targetDay, zone, window.lowerBound(),
                window.frontierEnd(), occupiedIncluding(occupied, validated.accepted()));
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

    /** The day's walls plus the blocks this run just materialized — the real occupancy from here on. */
    private static List<OccupiedInterval> occupiedIncluding(List<OccupiedInterval> walls,
                                                            List<AgendaBlock> accepted) {
        List<OccupiedInterval> occupancy = new ArrayList<>(walls);
        accepted.forEach(block -> occupancy.add(
            new OccupiedInterval(block.executableId(), block.start(), block.end(), false)));
        return occupancy;
    }

    /**
     * What each template slot's block already holds, keyed by slot — the shape the generator needs to
     * conserve a plan instead of rebuilding it. A block with no slot contributes nothing: it cannot be
     * matched to a window, and guessing would re-seat work under a band it never belonged to.
     */
    private static Map<String, List<UUID>> membershipBySlot(
        List<PlannerBlockIdentity.PersistedBlock> existingPlan) {
        Map<String, List<UUID>> bySlot = new LinkedHashMap<>();
        for (PlannerBlockIdentity.PersistedBlock block : existingPlan) {
            if (block.templateSlotId() != null && !block.members().isEmpty()) {
                bySlot.put(block.templateSlotId(), List.copyOf(block.members()));
            }
        }
        return bySlot;
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
     * The occupancy minus the run's own regenerable blocks (the day's planner-authored, still-planned
     * {@code TIME_BLOCK} executables). A prior materialization persists those blocks and they are
     * re-read as occupied walls; both the generator and the idempotency hash must exclude them — the
     * generator so a replan can re-place its own work instead of walling itself out, and the hash so a
     * redelivery of the same input digests identically (folding in the run's own output would
     * re-materialize on every redelivery). Every other wall stands: the read-only AGENDA windows and
     * the {@code TIME_BLOCK} executables that genuinely hold time — {@code USER} blocks and blocks
     * already closed among them.
     *
     * <p><b>Both sides of this subtraction now read the same model, and that is the point.</b> Between
     * ADR-040's step 1 and this one the subtraction was inert by construction: the walls came from
     * {@code core_executable} keyed by the block's own id, while the regenerable set still came from
     * the frozen table keyed by each member's id — two key spaces that designate different entities and
     * can never meet. Re-pointing the write path without re-pointing this read would have re-opened the
     * exact bug it exists to prevent: a replan walls itself out of today and pushes the whole day onto
     * tomorrow. The subtraction is by <b>block id</b>, which is now the only key either side speaks.
     */
    private static List<OccupiedInterval> withoutRegenerable(
        List<PlannerBlockIdentity.PersistedBlock> existingPlan, List<OccupiedInterval> occupied) {
        Set<UUID> regenerable = existingPlan.stream()
            .map(PlannerBlockIdentity.PersistedBlock::blockId)
            .collect(Collectors.toSet());
        if (regenerable.isEmpty()) {
            return occupied;
        }
        return occupied.stream()
            .filter(wall -> !regenerable.contains(wall.executableId()))
            .toList();
    }

    private static Set<UUID> readOnlyAgendaIds(List<OccupiedInterval> occupied) {
        return occupied.stream()
            .filter(OccupiedInterval::readOnlyAgenda)
            .map(OccupiedInterval::executableId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    }

    /**
     * The resolved, side-effect-free inputs the floor and the finalize step share for one day.
     * {@code state} is null when the day has no plannable forward window (a replan at/after bedtime);
     * {@link #plannable()} guards that case and {@code energyCriterion} still carries the readable
     * criterion for the empty agenda.
     */
    private record PreparedDay(
        PlannerDayState state,
        PlanningWindow window,
        List<OccupiedInterval> occupied,
        String energyCriterion
    ) {
        /** @return true when a forward window exists to plan (state is present) */
        boolean plannable() {
            return state != null;
        }

        /** An unplannable day (zero-width forward window): no state, no walls, criterion preserved. */
        static PreparedDay unplannable(PlanningWindow window, String energyCriterion) {
            return new PreparedDay(null, window, List.of(), energyCriterion);
        }
    }

    /**
     * The agenda to materialize plus its provenance: {@code fromAcceptedLlm} is true only for an LLM
     * proposal the {@code ProposalWallGuard} accepted, selecting the validator-bypass disposition in
     * {@link #finalizeMaterialization} (ADR-019 amendment). A DEGRADED or deterministic day carries
     * false, so it runs the floor's {@code AgendaValidator}. {@code floorAgenda} is always the
     * deterministic floor's output for the day (equal to {@code agenda} on the floor path), so the
     * multi-day replan can key its cross-day exclusion on the floor's coherent distribution.
     */
    private record MaterializationInput(Agenda agenda, boolean fromAcceptedLlm, Agenda floorAgenda) {
    }

    /**
     * One day's materialized agenda plus the executables the deterministic floor scheduled for it
     * (non-WIG), the anchor for the multi-day replan's cross-day exclusion.
     */
    record DayResult(Agenda agenda, List<UUID> floorScheduled) {
    }
}
