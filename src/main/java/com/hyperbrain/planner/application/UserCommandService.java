package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.SleepScoreInput;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Handles user commands from {@code user-commands.fifo} (HU-01b slice 2): the «calcular» button
 * (manual replan) and the manual Sleep Score input.
 *
 * <p>Idempotent per {@code command_id} via {@code processed_message} (SQS delivers at-least-once).
 * The whole handling runs in one transaction — mirroring {@code WriteCommandResultService} — so a
 * failure rolls back the dedup insert together with any partial effect and lets SQS redeliver.
 *
 * <p><b>Replan.</b> Reuses the delivery-slice path: the same {@link AgendaGenerationService} the
 * morning scheduler drives, but with {@code fromNow = true} and the command's {@code occurred_at}
 * as the reference instant (replan from when the user pressed the button). The regenerated blocks
 * reach iOS through the existing {@code AgendaBlockPlannedEvent} outbox path, and the per-user+day
 * delete-before-persist keeps repeats convergent. Unlike the morning dispatch there is no trigger
 * minute or once-per-day guard: a manual button press always replans. <b>Staleness guard
 * (Daniel, 2026-07-11):</b> a replan whose {@code occurred_at} is older than
 * {@code app.user-commands.replan-staleness-hours} (default 2) against the injected {@link Clock}
 * is discarded with a WARN — replanning from a long-gone instant (consumer backlog after downtime)
 * would plan against a stale "now". The command is still marked processed, so a redelivery of the
 * same stale command stays silent.
 *
 * <p><b>Sleep score.</b> Upserts a single {@code tel_sleep_record} row per local day through
 * {@link SleepScoreStore}, shaped so the energy resolution sees the score but the sleep-frontier
 * median never sees synthetic hours. Device precedence (Daniel, 2026-07-11): when the day is
 * already owned by a complete device record (hours + score), the manual score is discarded with a
 * WARN — see the store's contract.
 */
@Service
public class UserCommandService {

    private static final Logger log = LoggerFactory.getLogger(UserCommandService.class);

    private static final String DEDUP_PREFIX = "user-command:";

    private final ProcessedMessageStore processedMessageStore;
    private final AgendaGenerationService agendaGenerationService;
    private final PlannerStateRepository plannerStateRepository;
    private final SleepScoreStore sleepScoreStore;
    private final Clock clock;
    private final Duration replanStalenessBound;

    public UserCommandService(
        ProcessedMessageStore processedMessageStore,
        AgendaGenerationService agendaGenerationService,
        PlannerStateRepository plannerStateRepository,
        SleepScoreStore sleepScoreStore,
        Clock clock,
        @Value("${app.user-commands.replan-staleness-hours:2}") long replanStalenessHours
    ) {
        this.processedMessageStore = processedMessageStore;
        this.agendaGenerationService = agendaGenerationService;
        this.plannerStateRepository = plannerStateRepository;
        this.sleepScoreStore = sleepScoreStore;
        this.clock = clock;
        this.replanStalenessBound = Duration.ofHours(replanStalenessHours);
    }

    /**
     * Processes one user command: deduplicates by {@code command_id}, then routes by type. A
     * duplicate redelivery is logged and skipped without side effects.
     *
     * @param userId  the acting user (single-user MVP: the default user); never null
     * @param command the validated command; never null
     */
    @Transactional
    public void handle(UUID userId, UserCommand command) {
        if (!processedMessageStore.markProcessed(
                DEDUP_PREFIX + command.commandId(), command.type().name())) {
            log.warn("Duplicate user command {} ({}) ignored", command.commandId(), command.type());
            return;
        }
        switch (command.type()) {
            case REPLAN_AGENDA -> replanFromNow(userId, command);
            case SLEEP_SCORE -> recordSleepScore(userId, command.sleepScore(), command.occurredAt());
        }
    }

    private void replanFromNow(UUID userId, UserCommand command) {
        OffsetDateTime occurredAt = command.occurredAt();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (occurredAt.isBefore(now.minus(replanStalenessBound))) {
            log.warn("Stale replan command {} discarded: occurred_at {} is older than {}h (now {})",
                command.commandId(), occurredAt, replanStalenessBound.toHours(), now);
            return;
        }
        ZoneId zone = plannerStateRepository.loadUserZone(userId);
        LocalDate startDay = occurredAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate lastDay = occurredAt.plusHours(48).atZoneSameInstant(zone).toLocalDate();

        // Cross-day dedup: non-WIG tasks placed on an earlier day are excluded from later days so
        // the same task is never double-booked across the 48h window. WIG lead measures are exempt
        // (they are a daily recurring commitment, not a one-off task).
        Set<UUID> placed = new LinkedHashSet<>();
        for (LocalDate day = startDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            boolean fromNow = day.equals(startDay);
            Agenda agenda = agendaGenerationService.generate(userId, day, zone, occurredAt, fromNow, placed);
            agenda.blocks().stream()
                .filter(b -> !b.wig())
                .map(b -> b.executableId())
                .forEach(placed::add);
        }
        log.info("Manual replan executed for user {} on {} days ({} → {}) from {}",
            userId, lastDay.toEpochDay() - startDay.toEpochDay() + 1, startDay, lastDay, occurredAt);
    }

    private void recordSleepScore(UUID userId, SleepScoreInput input, OffsetDateTime occurredAt) {
        ZoneId zone = plannerStateRepository.loadUserZone(userId);
        boolean written = sleepScoreStore.upsertDailyScore(
            userId, input.date(), input.score(), occurredAt, zone);
        if (!written) {
            log.warn("Manual sleep score {} for user {} on {} discarded: "
                + "day owned by a complete device record", input.score(), userId, input.date());
            return;
        }
        log.info("Sleep score {} recorded for user {} on {}", input.score(), userId, input.date());
    }
}
