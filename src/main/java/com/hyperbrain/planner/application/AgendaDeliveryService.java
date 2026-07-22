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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Orchestrates the morning agenda dispatch (HU-01b delivery slice): decides whether the trigger
 * minute has arrived for the user's day, and if so hands the day off for generation. The scheduler
 * ({@code MorningAgendaScheduler}) calls {@link #dispatchIfDue} on a short cadence; the once-per-day
 * guard and the ±hysteresis clamp live here (and in the pure {@link MorningTriggerCalculator}),
 * keeping the scheduler thin.
 *
 * <p><b>Materialization cut-over (HU-01c H2).</b> Where the day is materialized depends on
 * {@code app.planner.materialization.async-enabled}:
 * <ul>
 *   <li><b>off (default)</b> — the legacy synchronous path: generate in-process, save the guard, and
 *       emit the empty-day proposal here when the window is empty. Byte-for-byte the previous
 *       behavior, so the cut-over deploys inert;</li>
 *   <li><b>on</b> — emit a {@code DailyAgendaRequestedEvent} to {@code ia-jobs} (guard + job committed
 *       atomically by {@link AgendaJobEmitter}); the single-owner {@code AgendaJobConsumer}
 *       materializes and owns the empty-day proposal.</li>
 * </ul>
 * The trigger guards (once-per-day, minute hysteresis) stay here in both modes — the emitter must
 * enqueue a job only when the minute is due, never one per poll.
 */
@Service
public class AgendaDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AgendaDeliveryService.class);

    /** How close to the trigger minute the scheduler cadence must land to fire (its own period). */
    private final Duration triggerTolerance;
    private final boolean asyncMaterializationEnabled;

    private final AgendaGenerationService agendaGenerationService;
    private final SleepFrontierCalculator sleepFrontierCalculator;
    private final MorningTriggerCalculator morningTriggerCalculator;
    private final MorningTriggerStore morningTriggerStore;
    private final PlannerStateRepository plannerStateRepository;
    private final EmptyAgendaNotifier emptyAgendaNotifier;
    private final AgendaJobEmitter agendaJobEmitter;

    public AgendaDeliveryService(
        AgendaGenerationService agendaGenerationService,
        SleepFrontierCalculator sleepFrontierCalculator,
        MorningTriggerCalculator morningTriggerCalculator,
        MorningTriggerStore morningTriggerStore,
        PlannerStateRepository plannerStateRepository,
        EmptyAgendaNotifier emptyAgendaNotifier,
        AgendaJobEmitter agendaJobEmitter,
        AgendaDeliveryProperties properties,
        @Value("${app.planner.materialization.async-enabled:false}") boolean asyncMaterializationEnabled
    ) {
        this.agendaGenerationService = agendaGenerationService;
        this.sleepFrontierCalculator = sleepFrontierCalculator;
        this.morningTriggerCalculator = morningTriggerCalculator;
        this.morningTriggerStore = morningTriggerStore;
        this.plannerStateRepository = plannerStateRepository;
        this.emptyAgendaNotifier = emptyAgendaNotifier;
        this.agendaJobEmitter = agendaJobEmitter;
        this.triggerTolerance = Duration.ofMinutes(properties.triggerToleranceMinutes());
        this.asyncMaterializationEnabled = asyncMaterializationEnabled;
    }

    /**
     * Dispatches the morning agenda for a user if the trigger minute for today has arrived and the
     * day has not been dispatched yet. Idempotent per user+day: a repeat call after the day has
     * fired is a no-op.
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

        MorningTriggerState firedState = new MorningTriggerState(trigger, today);
        if (asyncMaterializationEnabled) {
            // The guard save and the ia-jobs enqueue commit atomically; the single-owner consumer
            // materializes and owns the empty-day proposal.
            agendaJobEmitter.emitMorningJob(userId, today, zone, now, firedState);
            log.info("Morning dispatch enqueued for user {} on {} (trigger {})",
                userId, today, trigger.minutesOfDay());
            return true;
        }

        // Legacy synchronous path. The blocks + AgendaBlockPlannedEvent commit atomically inside
        // generate (its own transaction). The trigger-state save and the negative-case signal run
        // afterwards, outside any multi-statement transaction, so no SQS publish happens inside a
        // domain transaction.
        Agenda agenda = agendaGenerationService.generate(userId, today, zone, now, false);
        morningTriggerStore.save(userId, firedState);
        if (agenda.blocks().isEmpty()) {
            emptyAgendaNotifier.proposeNextDay(userId, today, agenda.energyCriterion(), now);
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
}
