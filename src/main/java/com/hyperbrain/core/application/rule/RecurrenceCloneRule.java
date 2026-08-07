package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.core.domain.model.CompletionState;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/*
 * Design pattern: Chain of Responsibility link
 * Reason: plugs into the DomainChangeProcessor chain without modifying the existing links;
 * the side effects (upsert + outbox) run inside the caller's ingestion transaction so the
 * clone is always consistent with its parent.
 */

/**
 * DR-04 — Recurrence cloning for any executable with {@code frequency > 0}.
 *
 * <p>When an executable with {@code frequency > 0} transitions to a <b>closed</b> status —
 * {@code DONE} or {@code FAILED} alike (ADR-039 never-miss-twice: a sanctioned miss still
 * schedules the next occurrence) — a clone is persisted immediately with
 * {@code start_time = original.start_time + frequency days} and
 * {@code end_time = original.end_time + frequency days} (null if the original had no end time),
 * and {@code status = TODO}. The clone carries the same user, parent, cycle, type, effort,
 * profile scales, recurrence metadata and the streak pair (copied after the closure outcome
 * updated it, so the chain survives cloning) — priority and urgency scores are left null for
 * the Prioritizer tick to recompute (ADR-020 D2).
 *
 * <p>An {@code ExecutableCreatedEvent} with {@code source_system = SYSTEM} is appended to the
 * Transactional Outbox so that both {@code NotionEventPropagator} and {@code AppleEventPropagator}
 * mirror the new recurrence to their respective satellites. The event deliberately uses
 * {@code SYSTEM} origin so the Notion propagator's loop guard does not block it (RF-17): the
 * original task change arrived from Notion (or Apple), but the clone is a SYSTEM derivation.
 *
 * <p>Guards: skipped when the executable is system-generated ({@code systemGenerated = true}),
 * when {@code frequency} is null or non-positive, when its type is exempt (see below), or when the
 * status did not transition to a closed one in this ingestion (idempotency — re-ingesting an
 * already-closed row never double-clones).
 *
 * <p><b>The AGENDA exception (ADR-040 D4).</b> Until now this rule was uniform across every type.
 * {@code AGENDA} is now the single exemption: it closes like anything else, but it <b>never
 * generates its next occurrence, not even with a {@code frequency}</b>. An AGENDA row mirrors a
 * calendar event the user does not own through this system (ADR-009/ADR-028: it is the immovable
 * floor of the day, and the calendar — not the domain — owns its recurrence). Cloning it here
 * would fabricate a second, domain-authored occurrence next to the one the calendar will deliver
 * on its own. The exemption is a property of the type, not of the caller, so it holds on both
 * paths: the ingestion chain and the ADR-040 day-close sweep.
 */
@Component
public class RecurrenceCloneRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceCloneRule.class);

    private static final String TODO = "TODO";

    /** Types exempt from recurrence cloning regardless of their frequency (ADR-040 D4). */
    private static final Set<String> NON_CLONING_TYPES = Set.of("AGENDA");

    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;

    public RecurrenceCloneRule(ExecutableStateRepository stateRepo, OutboxRepository outboxRepo) {
        this.stateRepo = stateRepo;
        this.outboxRepo = outboxRepo;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.systemGenerated() || !hasFrequency(merged) || !becameClosed(previous, merged)) {
            return merged;
        }
        if (NON_CLONING_TYPES.contains(merged.type())) {
            log.info("Executable {} of type {} closed as {} with frequency {}; recurrence cloning "
                    + "suppressed (ADR-040 D4: the calendar owns an AGENDA's next occurrence)",
                merged.id(), merged.type(), merged.status(), merged.frequency());
            return merged;
        }
        ExecutableSnapshot clone = buildClone(merged);
        stateRepo.upsertExecutable(clone);
        stateRepo.copyStreaks(merged.id(), clone.id());
        appendCreatedEvent(clone);
        log.info("Executable {} closed as {}; cloned as {} (frequency {} days, next due {})",
            merged.id(), merged.status(), clone.id(), merged.frequency().longValue(),
            clone.startTime());
        return merged;
    }

    private static boolean hasFrequency(ExecutableSnapshot s) {
        return s.frequency() != null && s.frequency() > 0;
    }

    private static boolean becameClosed(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return CompletionState.isClosed(merged.status())
            && (previous == null || !CompletionState.isClosed(previous.status()));
    }

    private static ExecutableSnapshot buildClone(ExecutableSnapshot source) {
        OffsetDateTime nextDue = source.startTime() != null
            ? source.startTime().plusDays(source.frequency().longValue())
            : null;
        OffsetDateTime nextEndTime = source.endTime() != null
            ? source.endTime().plusDays(source.frequency().longValue())
            : null;
        return new ExecutableSnapshot(
            UUID.randomUUID(),
            source.userId(),
            source.parentId(),
            source.cycleId(),
            source.name(),
            source.description(),
            source.type(),
            TODO,
            null,
            null,
            source.effortScore(),
            source.isImportant(),
            source.frequency(),
            nextDue,
            nextEndTime,
            source.sourceCalendar(),
            source.energyDrain(),
            source.mentalLoad(),
            source.impact(),
            false,
            // The clone is born un-contained (container_block_id = null): TIME_BLOCK blocks are
            // ephemeral per-day containers that settle, they never persist across occurrences, and
            // block (re)assignment is a plan-time concern. Inheriting the source's block would let
            // ContainmentCopyRule re-assert today's block window over the clone's +frequency slot on
            // the next ingestion — a self-reasserting date corruption. Detached, it keeps its shifted
            // slot until the planner places it (core#59).
            null);
    }

    private void appendCreatedEvent(ExecutableSnapshot clone) {
        outboxRepo.append(ExecutableOutboxEvents.created(clone.id()));
    }
}
