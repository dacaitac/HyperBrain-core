package com.hyperbrain.prioritizer.application;

import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Reflects a whole-day reprioritization to the satellites (#66a, scheduled tick tier (a)).
 *
 * <p>Reprioritizes the user's day and, <b>in the same transaction</b>, appends one outbox event per
 * executable whose score actually moved — never for the whole day. It reuses the existing
 * executable-upsert propagation path ({@code CORE_EXECUTABLE} + {@code ExecutableUpdatedEvent}) so
 * no new event type or contract is introduced: the {@code NotionEventPropagator} re-reads each row
 * and mirrors the fresh {@code Priority Score} / {@code Urgence}. The {@code SYSTEM} origin keeps the
 * events eligible for outbound propagation (the drain's loop protection only suppresses the target's
 * own origin), so the recompute reaches Notion (and Apple) but never loops back in.
 *
 * <p>Score persistence and the outbox append share one transaction (Transactional Outbox guarantee):
 * a row that changed is either persisted <em>and</em> staged for propagation, or neither.
 */
@Service
public class PriorityReflectionService {

    private static final Logger log = LoggerFactory.getLogger(PriorityReflectionService.class);

    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String EXECUTABLE_UPDATED_EVENT = "ExecutableUpdatedEvent";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final PrioritizerService prioritizerService;
    private final OutboxRepository outboxRepo;

    public PriorityReflectionService(PrioritizerService prioritizerService,
                                     OutboxRepository outboxRepo) {
        this.prioritizerService = prioritizerService;
        this.outboxRepo = outboxRepo;
    }

    /**
     * Reprioritizes the user's day and stages the outbound reflection for every changed executable.
     *
     * @param userId the user whose day to reprioritize
     * @return how many executables were staged for reflection (the changed count)
     */
    @Transactional
    public int reflectDailyReprioritization(UUID userId) {
        Set<UUID> changed = prioritizerService.reprioritizeToday(userId);
        OffsetDateTime now = OffsetDateTime.now();
        for (UUID executableId : changed) {
            outboxRepo.append(new OutboxEvent(
                UUID.randomUUID(), EXECUTABLE_AGGREGATE, executableId.toString(),
                EXECUTABLE_UPDATED_EVENT, minimalPayload(), SOURCE_SYSTEM, now));
        }
        log.info("Daily reprioritization for user {} staged {} score reflections", userId, changed.size());
        return changed.size();
    }

    /**
     * The propagator re-reads the row and needs only the local id (carried in {@code aggregate_id});
     * the operation marker keeps the payload consistent with the sync-produced upsert events.
     */
    private static String minimalPayload() {
        return "{\"operation\":\"UPDATED\"}";
    }
}
