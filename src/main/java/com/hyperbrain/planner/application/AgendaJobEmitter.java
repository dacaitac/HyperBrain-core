package com.hyperbrain.planner.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.planner.domain.model.DailyAgendaRequestedEvent;
import com.hyperbrain.planner.domain.model.MorningTriggerState;
import com.hyperbrain.planner.domain.port.out.MorningTriggerStore;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Emits a {@link DailyAgendaRequestedEvent} to {@code ia-jobs} through the Transactional Outbox
 * (HU-01c H2). This is how the plan <em>triggers</em> hand work to the single materialization owner
 * without ever generating in-process: the job row and the trigger's own guard commit in one
 * transaction, so a job is queued if and only if the trigger fired (never one per scheduler poll).
 *
 * <p>The event is appended with {@code aggregate_type = IA_JOB}, so the {@code OutboxWorker} routes it
 * via {@code SqsEventPublisher} to {@code ia-jobs}; no propagator targets IA jobs, so the drain
 * publishes and marks it processed with no satellite fan-out.
 */
@Service
public class AgendaJobEmitter {

    private static final Logger log = LoggerFactory.getLogger(AgendaJobEmitter.class);

    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final OutboxRepository outboxRepository;
    private final MorningTriggerStore morningTriggerStore;
    private final ObjectMapper objectMapper;

    public AgendaJobEmitter(
        OutboxRepository outboxRepository,
        MorningTriggerStore morningTriggerStore,
        ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.morningTriggerStore = morningTriggerStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Emits the morning-run job and records the once-per-day guard atomically. Called by
     * {@code AgendaDeliveryService} when the trigger minute has arrived and the day has not fired yet;
     * saving the guard together with the job keeps a later poll from emitting a second job.
     *
     * @param userId    the user whose day to plan; never null
     * @param today     the day being planned; never null
     * @param zone      the timezone the trigger resolved the local day in; never null
     * @param now       the reference instant, frozen into the job; never null
     * @param triggerState the morning-trigger guard to persist for {@code today}; never null
     */
    @Transactional
    public void emitMorningJob(UUID userId, LocalDate today, ZoneId zone, OffsetDateTime now,
                               MorningTriggerState triggerState) {
        morningTriggerStore.save(userId, triggerState);
        append(new DailyAgendaRequestedEvent(userId, today, zone.getId(), now, false));
        log.info("Enqueued morning agenda job for user {} on {} (T={})", userId, today, now);
    }

    /**
     * Emits a replan-from-now job. Called by {@code UserCommandService} from within the command's dedup
     * transaction, so the job and the {@code processed_message} mark commit together — a redelivery of
     * the same command neither re-emits nor re-materializes.
     *
     * @param userId     the user whose day to replan; never null
     * @param startDay   the local day the replan starts on; never null
     * @param zone       the user's timezone; never null
     * @param occurredAt the instant the user pressed «calcular», frozen into the job; never null
     */
    @Transactional
    public void emitReplanJob(UUID userId, LocalDate startDay, ZoneId zone, OffsetDateTime occurredAt) {
        append(new DailyAgendaRequestedEvent(userId, startDay, zone.getId(), occurredAt, true));
        log.info("Enqueued replan agenda job for user {} from {} (T={})", userId, startDay, occurredAt);
    }

    private void append(DailyAgendaRequestedEvent event) {
        outboxRepository.append(new OutboxEvent(
            UUID.randomUUID(),
            DailyAgendaRequestedEvent.AGGREGATE_TYPE,
            event.userId().toString(),
            DailyAgendaRequestedEvent.EVENT_TYPE,
            serialize(event),
            SOURCE_SYSTEM,
            event.referenceInstant()));
    }

    private String serialize(DailyAgendaRequestedEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("user_id", event.userId().toString());
        node.put("agenda_date", event.agendaDate().toString());
        node.put("zone_id", event.zoneId());
        node.put("reference_instant", event.referenceInstant().toString());
        node.put("from_now", event.fromNow());
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize DailyAgendaRequestedEvent", ex);
        }
    }
}
