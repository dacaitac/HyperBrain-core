package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Design pattern: Chain of Responsibility link
 * Reason: plugs into the DomainChangeProcessor chain without modifying the existing links;
 * the side effects (upsert + outbox) run inside the caller's ingestion transaction so the
 * clone is always consistent with its parent.
 */

/**
 * DR-04 — Habit recurrence cloning.
 *
 * <p>When an executable with {@code frequency > 0} transitions to {@code DONE}, a clone is
 * persisted immediately with {@code start_time = original.start_time + frequency days} and
 * {@code status = TODO}. The clone carries the same user, parent, cycle, type, effort,
 * profile scales, and recurrence metadata — priority and urgency scores are left null for the
 * Prioritizer tick to recompute (ADR-020 D2).
 *
 * <p>An {@code ExecutableCreatedEvent} with {@code source_system = SYSTEM} is appended to the
 * Transactional Outbox so that both {@code NotionEventPropagator} and {@code AppleEventPropagator}
 * mirror the new recurrence to their respective satellites. The event deliberately uses
 * {@code SYSTEM} origin so the Notion propagator's loop guard does not block it (RF-17): the
 * original task change arrived from Notion (or Apple), but the clone is a SYSTEM derivation.
 *
 * <p>Guards: skipped when the executable is system-generated ({@code systemGenerated = true}),
 * when {@code frequency} is null or non-positive, or when the status did not transition to
 * {@code DONE} in this ingestion (idempotency — re-ingesting an already-DONE row never
 * double-clones).
 */
@Component
public class HabitRecurrenceRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(HabitRecurrenceRule.class);

    private static final String DONE = "DONE";
    private static final String TODO = "TODO";
    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;

    public HabitRecurrenceRule(ExecutableStateRepository stateRepo, OutboxRepository outboxRepo) {
        this.stateRepo = stateRepo;
        this.outboxRepo = outboxRepo;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.systemGenerated() || !hasFrequency(merged) || !becameDone(previous, merged)) {
            return merged;
        }
        ExecutableSnapshot clone = buildClone(merged);
        stateRepo.upsertExecutable(clone);
        appendCreatedEvent(clone);
        log.info("Habit {} cloned as {} (frequency {} days, next due {})",
            merged.id(), clone.id(), merged.frequency().longValue(), clone.startTime());
        return merged;
    }

    private static boolean hasFrequency(ExecutableSnapshot s) {
        return s.frequency() != null && s.frequency() > 0;
    }

    private static boolean becameDone(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return DONE.equals(merged.status())
            && (previous == null || !DONE.equals(previous.status()));
    }

    private static ExecutableSnapshot buildClone(ExecutableSnapshot source) {
        OffsetDateTime nextDue = source.startTime() != null
            ? source.startTime().plusDays(source.frequency().longValue())
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
            null,
            source.sourceCalendar(),
            source.energyDrain(),
            source.mentalLoad(),
            source.impact(),
            false);
    }

    private void appendCreatedEvent(ExecutableSnapshot clone) {
        String payload = String.format("{\"local_id\":\"%s\",\"operation\":\"CREATED\"}", clone.id());
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, clone.id().toString(),
            "ExecutableCreatedEvent", payload, SOURCE_SYSTEM, OffsetDateTime.now()));
    }
}
