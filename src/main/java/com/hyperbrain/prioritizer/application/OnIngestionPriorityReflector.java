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
 *   <li>The score is always recomputed and persisted ({@link RescoreResult#moved()} is the
 *       Prioritizer's own epsilon-guarded verdict).
 *   <li><b>NOTION origin:</b> a {@code source_system=SYSTEM} {@code ExecutableUpdatedEvent} carrying
 *       the {@code reflection=PRIORITY_SCORE} marker is <em>always</em> staged, regardless of whether
 *       the score moved. It re-asserts the SYSTEM-owned scores onto Notion: {@code SourceAwareMerge}
 *       pins the scores to the domain value and ignores Notion's, so a manual score edit must be
 *       overwritten, and the Notion propagator's loop protection (RF-17) suppresses the NOTION-origin
 *       event. The marker makes the propagator PATCH only the score fields, so the human-owned fields
 *       the user just edited are never touched.
 *   <li><b>APPLE / SYSTEM origin:</b> those origins' own outbox events are propagated to Notion by
 *       the propagator (they are not loop-protected), so no extra SYSTEM event is needed. The caller
 *       may inspect the return value to decide whether the score moved.
 * </ul>
 *
 * <p>The SYSTEM append happens inside the caller's ingestion transaction (the append is a plain
 * {@code INSERT} on {@code outbox_events}), preserving the Transactional Outbox guarantee: the
 * persisted score and its reflection commit together or not at all.
 */
@Service
public class OnIngestionPriorityReflector {

    private static final Logger log = LoggerFactory.getLogger(OnIngestionPriorityReflector.class);

    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String EXECUTABLE_UPDATED_EVENT = "ExecutableUpdatedEvent";
    private static final String SYSTEM_SOURCE = "SYSTEM";
    // The "reflection":"PRIORITY_SCORE" marker tells the NotionEventPropagator this write-back only
    // touches the SYSTEM-owned score fields, so it PATCHes just those (never the full page mirror)
    // and skips the outbound human-edit pre-read: the user never edits scores, so a burst of manual
    // edits can never collide with it (ADR-020 write-back field scoping).
    private static final String REFLECTION_PAYLOAD =
        "{\"operation\":\"UPDATED\",\"reflection\":\"PRIORITY_SCORE\"}";

    private final PrioritizerService prioritizerService;
    private final OutboxRepository outboxRepo;

    public OnIngestionPriorityReflector(PrioritizerService prioritizerService,
                                        OutboxRepository outboxRepo) {
        this.prioritizerService = prioritizerService;
        this.outboxRepo = outboxRepo;
    }

    /**
     * Recomputes the executable's Priority Score on its persisted merged state and stages the
     * outbound reflection appropriate to the ingestion origin (see the class documentation). Must
     * be called after the merged row has been upserted, inside the ingestion transaction.
     *
     * <p>For NOTION origin a {@code source_system=SYSTEM} {@code ExecutableUpdatedEvent} marked
     * {@code reflection=PRIORITY_SCORE} is <em>always</em> staged (the score may or may not have
     * moved — the SYSTEM-owned scores must be re-asserted onto Notion regardless). For APPLE/SYSTEM
     * origin no extra event is staged because those origins' own events are already propagated to
     * Notion by the propagator.
     *
     * @param executableId the just-upserted executable to rescore
     * @param origin       the external system that produced the inbound change; decides whether a
     *                     SYSTEM reflection event is staged
     * @return {@code true} if a SYSTEM {@code ExecutableUpdatedEvent} was staged (NOTION origin),
     *         {@code false} for all other origins
     */
    public boolean reflect(UUID executableId, ExternalSystem origin) {
        RescoreResult result = prioritizerService.rescore(executableId);
        if (mirrorsOwnChangeToNotion(origin)) {
            // APPLE/SYSTEM origins: their own outbox events are propagated to Notion by the
            // propagator — no extra SYSTEM event is needed. The score is still persisted above.
            return false;
        }
        // NOTION origin: always stage a SYSTEM reflection so the SYSTEM-owned scores are re-asserted
        // onto Notion regardless of whether the score moved. SourceAwareMerge pins the scores to the
        // domain value and ignores whatever Notion held, so a manual score edit in Notion must be
        // overwritten here; the NOTION-origin event alone cannot do it (RF-17 loop protection
        // suppresses it). The marked payload makes the propagator PATCH only the score fields — every
        // human-owned field the user just edited in Notion is left exactly as they typed it.
        outboxRepo.append(new OutboxEvent(UUID.randomUUID(), EXECUTABLE_AGGREGATE,
            executableId.toString(), EXECUTABLE_UPDATED_EVENT, REFLECTION_PAYLOAD, SYSTEM_SOURCE,
            OffsetDateTime.now()));
        log.debug("Staged SYSTEM reflection for executable {} ({} ingestion, score moved={})",
            executableId, origin, result.moved());
        return true;
    }

    /**
     * Whether an ingestion of this origin already mirrors its own change to Notion. Only NOTION
     * ingestion does not: the Notion propagator suppresses events of its own origin (RF-17), so
     * without an explicit SYSTEM event the Core state would never reach Notion. Every other origin's
     * event is propagated to Notion, which re-reads the row and carries the state along.
     */
    private static boolean mirrorsOwnChangeToNotion(ExternalSystem origin) {
        return origin != ExternalSystem.NOTION;
    }
}
