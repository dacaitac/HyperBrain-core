package com.hyperbrain.prioritizer.application;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Central on-event priority reflection for the ingestion pipeline (#66a, ADR-020 D2). Every inbound
 * ingestion path — Notion tasks and Apple reminders/events today, any future source tomorrow —
 * delegates to {@link #reflect} <b>after</b> upserting the merged row, so the guarantee "on any
 * ingestion, recompute the priority on the persisted merged state" lives in one place instead of
 * being copied inline per service.
 *
 * <p>The rescore reads the executable straight from the persisted row, so callers must invoke this
 * only after their {@code executableRepo.upsert(...)}; running it before the upsert scores a stale
 * pre-merge snapshot (the original bug) and, for urgency, misses the freshly persisted due date.
 *
 * <p>What happens once the score is recomputed is decided <b>here</b>, keyed on the ingestion origin,
 * so the branching never leaks back into the handlers:
 * <ul>
 *   <li>The score is always recomputed and persisted (a moved value is stored, an unchanged one is a
 *       clean no-op — {@link RescoreResult#moved()} is the Prioritizer's own epsilon-guarded verdict).
 *   <li>When it moved and the origin does <b>not</b> already mirror its own change to Notion
 *       (a NOTION-origin ingestion, whose event the Notion propagator ignores by loop protection,
 *       RF-17), a dedicated {@code source_system=SYSTEM} {@code ExecutableUpdatedEvent} is staged so
 *       the SYSTEM-authored score still reaches Notion.
 *   <li>When it moved but the origin already mirrors its own change (APPLE — the Notion propagator
 *       does propagate {@code APPLE}-origin events, re-reading the row post-commit), no extra event
 *       is staged: the origin's own event carries the fresh score outward.
 * </ul>
 *
 * <p>The optional SYSTEM append happens inside the caller's ingestion transaction (the append is a
 * plain {@code INSERT} on {@code outbox_events}), preserving the Transactional Outbox guarantee: the
 * persisted score and its reflection commit together or not at all.
 */
@Service
public class OnIngestionPriorityReflector {

    private static final Logger log = LoggerFactory.getLogger(OnIngestionPriorityReflector.class);

    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String EXECUTABLE_UPDATED_EVENT = "ExecutableUpdatedEvent";
    private static final String SYSTEM_SOURCE = "SYSTEM";
    private static final String REFLECTION_PAYLOAD = "{\"operation\":\"UPDATED\"}";

    private final PrioritizerService prioritizerService;
    private final OutboxRepository outboxRepo;

    public OnIngestionPriorityReflector(PrioritizerService prioritizerService,
                                        OutboxRepository outboxRepo) {
        this.prioritizerService = prioritizerService;
        this.outboxRepo = outboxRepo;
    }

    /**
     * Recomputes the executable's Priority Score on its persisted merged state and, when the score
     * moved, stages the outbound reflection appropriate to the ingestion origin (see the class
     * documentation). Must be called after the merged row has been upserted, inside the ingestion
     * transaction.
     *
     * @param executableId the just-upserted executable to rescore
     * @param origin       the external system that produced the inbound change; decides whether a
     *                     SYSTEM reflection event is needed
     */
    public void reflect(UUID executableId, ExternalSystem origin) {
        RescoreResult result = prioritizerService.rescore(executableId);
        if (!result.moved()) {
            return;
        }
        if (mirrorsOwnChangeToNotion(origin)) {
            // The origin's own outbox event already carries the fresh score to Notion; adding a
            // SYSTEM event would duplicate the reflection.
            return;
        }
        outboxRepo.append(new OutboxEvent(UUID.randomUUID(), EXECUTABLE_AGGREGATE,
            executableId.toString(), EXECUTABLE_UPDATED_EVENT, REFLECTION_PAYLOAD, SYSTEM_SOURCE,
            OffsetDateTime.now()));
        log.debug("Staged SYSTEM priority reflection for executable {} (score moved on {} ingestion)",
            executableId, origin);
    }

    /**
     * Whether an ingestion of this origin already mirrors its own change to Notion. Only NOTION
     * ingestion does not: the Notion propagator suppresses events of its own origin (RF-17), so its
     * recomputed score would otherwise be stranded. Every other origin's event is propagated to
     * Notion, which re-reads the row and carries the score along.
     */
    private static boolean mirrorsOwnChangeToNotion(ExternalSystem origin) {
        return origin != ExternalSystem.NOTION;
    }
}
