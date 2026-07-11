package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MorningTriggerState;
import com.hyperbrain.planner.domain.model.SleepWindow;
import com.hyperbrain.planner.domain.port.out.MorningTriggerStore;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.MorningTriggerCalculator;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import com.hyperbrain.planner.infrastructure.AgendaDeliveryProperties;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import com.hyperbrain.sync.infrastructure.WriteCommandWireMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Orchestrates the morning agenda dispatch (HU-01b delivery slice): decides whether the trigger
 * minute has arrived for the user's day, and if so generates the day and lets the outbox carry the
 * blocks to iOS. The scheduler ({@code MorningAgendaScheduler}) calls {@link #dispatchIfDue} on a
 * short cadence; the once-per-day guard and the ±hysteresis clamp live here (and in the pure
 * {@link MorningTriggerCalculator}), keeping the scheduler thin.
 *
 * <p><b>Negative case.</b> When the day yields no useful blocks (e.g. a run after bedtime), the
 * service never delivers empty reminders: it proposes the next day and emits a single readable
 * "no blocks today" signal reminder so the user is told, not left with silence (Triángulo de
 * Control). This orchestration concern lives here, not in the deterministic generator.
 */
@Service
public class AgendaDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AgendaDeliveryService.class);

    private static final String COMMAND_ID_NAMESPACE = "hyperbrain-agenda-empty-command:";
    private static final String SIGNAL_LOCAL_ID_NAMESPACE = "hyperbrain-agenda-empty-signal:";
    private static final String STATUS_PENDING = "PENDING";
    private static final String REMINDER_LIST_NAME = "HyperBrain";
    private static final String EMPTY_DAY_TITLE = "No agenda blocks today";
    private static final String EMPTY_DAY_BODY =
        "No useful blocks fit today's window — planned for tomorrow instead.";

    /** How close to the trigger minute the scheduler cadence must land to fire (its own period). */
    private final Duration triggerTolerance;

    private final AgendaGenerationService agendaGenerationService;
    private final SleepFrontierCalculator sleepFrontierCalculator;
    private final MorningTriggerCalculator morningTriggerCalculator;
    private final MorningTriggerStore morningTriggerStore;
    private final PlannerStateRepository plannerStateRepository;
    private final WriteCommandPublisher commandPublisher;
    private final WriteCommandLogRepository commandLogRepo;
    private final WriteCommandWireMapper wireMapper;

    public AgendaDeliveryService(
        AgendaGenerationService agendaGenerationService,
        SleepFrontierCalculator sleepFrontierCalculator,
        MorningTriggerCalculator morningTriggerCalculator,
        MorningTriggerStore morningTriggerStore,
        PlannerStateRepository plannerStateRepository,
        WriteCommandPublisher commandPublisher,
        WriteCommandLogRepository commandLogRepo,
        WriteCommandWireMapper wireMapper,
        AgendaDeliveryProperties properties
    ) {
        this.agendaGenerationService = agendaGenerationService;
        this.sleepFrontierCalculator = sleepFrontierCalculator;
        this.morningTriggerCalculator = morningTriggerCalculator;
        this.morningTriggerStore = morningTriggerStore;
        this.plannerStateRepository = plannerStateRepository;
        this.commandPublisher = commandPublisher;
        this.commandLogRepo = commandLogRepo;
        this.wireMapper = wireMapper;
        this.triggerTolerance = Duration.ofMinutes(properties.triggerToleranceMinutes());
    }

    /**
     * Dispatches the morning agenda for a user if the trigger minute for today has arrived and the
     * day has not been dispatched yet. Idempotent per user+day: a repeat call after the day has
     * fired is a no-op, and the underlying generation replaces the day's blocks rather than
     * accumulating them.
     *
     * @param userId the user whose day to dispatch; never null
     * @param zone   the user's timezone; never null
     * @param now    the reference instant; never null
     * @return true when a dispatch fired on this call, false when it was not due or already fired
     */
    public boolean dispatchIfDue(UUID userId, ZoneId zone, OffsetDateTime now) {
        MorningTriggerState state = morningTriggerStore.load(userId);
        LocalDate today = now.atZoneSameInstant(zone).toLocalDate();
        if (state.firedOn(today)) {
            return false;
        }

        SleepWindow sleepWindow = sleepFrontierCalculator.computeWindow(
            plannerStateRepository.loadSleepFrontierInputs(userId, now));
        LocalTimeOfDay trigger = morningTriggerCalculator.resolveTrigger(sleepWindow, state);
        if (!triggerReached(trigger, zone, now)) {
            return false;
        }

        // The blocks + AgendaBlockPlannedEvent commit atomically inside generate (its own
        // transaction). The trigger-state save and the negative-case signal run afterwards, outside
        // any multi-statement transaction, so no SQS publish happens inside a domain transaction.
        Agenda agenda = agendaGenerationService.generate(userId, today, zone, now, false);
        morningTriggerStore.save(userId, new MorningTriggerState(trigger, today));

        if (agenda.blocks().isEmpty()) {
            proposeNextDay(userId, today, agenda.energyCriterion(), now);
        }
        log.info("Morning dispatch fired for user {} on {} (trigger {}); {} block(s) delivered",
            userId, today, trigger.minutesOfDay(), agenda.blocks().size());
        return true;
    }

    /**
     * Whether {@code now} has reached the trigger minute-of-day within the scheduler tolerance. The
     * gate is one-sided (at or after the trigger) so a scheduler tick that lands slightly late still
     * fires; the once-per-day guard prevents a second fire the same day.
     */
    private boolean triggerReached(LocalTimeOfDay trigger, ZoneId zone, OffsetDateTime now) {
        ZonedDateTime local = now.atZoneSameInstant(zone);
        int nowMinutes = local.getHour() * 60 + local.getMinute();
        int triggerMinutes = trigger.minutesOfDay();
        int toleranceMinutes = (int) triggerTolerance.toMinutes();
        return nowMinutes >= triggerMinutes && nowMinutes <= triggerMinutes + toleranceMinutes;
    }

    /**
     * Emits the "no blocks today, planned for tomorrow" signal reminder directly, bypassing the
     * block outbox path (there are no blocks to carry). The command id is deterministic per user+day
     * so a retried dispatch never doubles the signal.
     */
    private void proposeNextDay(UUID userId, LocalDate today, String energyCriterion,
                                OffsetDateTime now) {
        UUID commandId = deterministicId(COMMAND_ID_NAMESPACE, userId, today);
        UUID signalLocalId = deterministicId(SIGNAL_LOCAL_ID_NAMESPACE, userId, today);
        String body = energyCriterion != null && !energyCriterion.isBlank()
            ? EMPTY_DAY_BODY + "\n\n" + energyCriterion.trim()
            : EMPTY_DAY_BODY;
        ReminderPayload payload = new ReminderPayload(
            EMPTY_DAY_TITLE, body, now, false, 0, "", REMINDER_LIST_NAME);
        WriteCommand command =
            new WriteCommand(commandId, CommandType.REMINDER, Operation.CREATED, null, payload);

        commandLogRepo.upsertPending(new PendingWriteCommand(
            commandId, userId, signalLocalId, CommandType.REMINDER, Operation.CREATED, null,
            wireMapper.payloadJson(payload), STATUS_PENDING));
        commandPublisher.publish(command, signalLocalId.toString());
        log.info("No blocks fit today for user {} on {}; emitted next-day proposal signal {}",
            userId, today, commandId);
    }

    private static UUID deterministicId(String namespace, UUID userId, LocalDate day) {
        return UUID.nameUUIDFromBytes(
            (namespace + userId + ":" + day).getBytes(StandardCharsets.UTF_8));
    }
}
