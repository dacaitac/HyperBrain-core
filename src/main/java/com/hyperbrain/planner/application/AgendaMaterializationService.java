package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.DailyAgendaRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Set;

/**
 * The single owner of agenda materialization (HU-01c H2). Every {@link DailyAgendaRequestedEvent} —
 * whatever trigger emitted it — is generated and persisted here and nowhere else, so the "what the
 * agenda produces" logic has exactly one home and enclosing the LLM tier later (H3–H5) is a change to
 * this one collaborator, not to every trigger.
 *
 * <p>The job's {@code fromNow} flag selects the run shape, mirroring the two legacy paths this
 * consolidates:
 * <ul>
 *   <li><b>morning run</b> ({@code fromNow = false}) — a single idempotent day; when it yields no
 *       useful blocks the empty-day proposal is staged in the same transaction as the claim (the
 *       negative case now belongs to the owner of the result, atomically via the Transactional
 *       Outbox);</li>
 *   <li><b>replan run</b> ({@code fromNow = true}) — the whole 48 h window, idempotent as a unit.</li>
 * </ul>
 *
 * <p><b>Graceful degradation (ADR-019).</b> The deterministic humanized floor is the generator today
 * and is resilient by construction: incomplete inputs yield a DEGRADED plan (a flag on the agenda),
 * never an exception, so the day still materializes and the job is acknowledged. Genuine failures
 * (persistence, transport) propagate so SQS redelivers and, past the redrive threshold, parks the job
 * on the {@code ia-jobs} DLQ — never a silent loss, never a poisoned queue. When H3 introduces the
 * fallible LLM primary, its «propose → validate → fall back to the floor» seam lands in this method,
 * behind the same job contract; the visibility heartbeat the consumer already prepares covers the
 * longer LLM run.
 */
@Service
public class AgendaMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(AgendaMaterializationService.class);

    private final AgendaGenerationService generationService;

    public AgendaMaterializationService(AgendaGenerationService generationService) {
        this.generationService = generationService;
    }

    /**
     * Materializes the requested day (or replan window), idempotently. Safe to call for an
     * at-least-once redelivery: the underlying {@code (user, day, input_hash)} claim makes a repeat of
     * the same input a no-op.
     *
     * @param job the requested materialization; never null
     */
    public void materialize(DailyAgendaRequestedEvent job) {
        ZoneId zone = ZoneId.of(job.zoneId());
        if (job.fromNow()) {
            boolean replanned = generationService.materializeReplanIfNew(
                job.userId(), job.referenceInstant(), zone);
            if (!replanned) {
                log.debug("Replan job for user {} from {} deduplicated", job.userId(), job.agendaDate());
            }
            return;
        }
        // Morning run: materialize the single day. The empty-day proposal (if the window is empty) is
        // staged inside the materialization transaction, so there is nothing to orchestrate here
        // post-commit.
        generationService.materializeIfNew(
            job.userId(), job.agendaDate(), zone, job.referenceInstant(), false, Set.of());
    }
}
