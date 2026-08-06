package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.application.TimeBlockSettlementService;
import com.hyperbrain.core.application.event.FocusSwitchedPayload;
import com.hyperbrain.core.domain.model.FocusCandidate;
import com.hyperbrain.core.domain.model.SnapshotSubtask;
import com.hyperbrain.core.domain.model.TimeBlockExecutable;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.core.domain.port.out.TimeBlockExecutableRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DR-05 + DR-06 (ADR-013 D3) — single focus invariant and focus-switch cut.
 *
 * <p>Activating a controllable executable ({@code type != AGENDA}, not system-generated) is
 * the explicit declaration of a focus change. For every other controllable IN_PROGRESS task
 * still holding an ACTIVE block, this rule — inside the same ingestion transaction:
 * <ol>
 *   <li>settles its block (DR-08, gross minutes; AGENDA clock suspension deferred to HU-02),
 *   <li>freezes the executed stretch as a completed {@code system_generated} snapshot subtask
 *       carrying the original effort labels and window,
 *   <li>flags the cut task {@code pending_reestimation} while preserving its last known effort
 *       values (mirrors keep echoing those values — the cut is a soft hint to re-estimate, never
 *       a data-destroying clear that the full-mirror propagators would push to the satellites), and
 *   <li>emits {@code FocusSwitchedEvent} plus the mirroring events through the outbox.
 * </ol>
 * Finally it auto-opens an {@code ACTIVE/FOCUS} block for the new focus so imputation has a
 * window before the Planner engine exists.
 *
 * <p>Legacy fallback: when no task holds an ACTIVE block, controllable IN_PROGRESS tasks that
 * pre-date the block model (zero blocks) are cut with the punctual window {@code [now, now]}
 * and a zero actual duration — the honest datum is "we do not know how long it ran".
 *
 * <p>AGENDA executables never cut (they are walls, not intentions), and Apple cannot originate
 * IN_PROGRESS, so in practice the trigger arrives from Notion or future SYSTEM engines.
 */
@Component
public class SingleFocusRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(SingleFocusRule.class);

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String AGENDA = "AGENDA";
    private static final String TIME_BLOCK = "TIME_BLOCK";
    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final ExecutableStateRepository stateRepo;
    private final TimeBlockExecutableRepository timeBlockRepo;
    private final TimeBlockSettlementService settlementService;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public SingleFocusRule(
        ExecutableStateRepository stateRepo,
        TimeBlockExecutableRepository timeBlockRepo,
        TimeBlockSettlementService settlementService,
        OutboxRepository outboxRepo,
        ObjectMapper objectMapper
    ) {
        this.stateRepo = stateRepo;
        this.timeBlockRepo = timeBlockRepo;
        this.settlementService = settlementService;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (!becomesFocus(previous, merged) || stateRepo.isSystemGenerated(merged.id())) {
            return merged;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<FocusCandidate> candidates = stateRepo.findActiveFocus(merged.userId(), merged.id());
        if (candidates.isEmpty()) {
            candidates = stateRepo.findLegacyInProgress(merged.userId(), merged.id());
        }
        candidates.forEach(candidate -> cut(candidate, merged.id(), now));
        // The rules run before the ingestion upsert: on CREATE the new focus row does not
        // exist yet, so its FOCUS block would violate the FK. A task born IN_PROGRESS opens
        // its block on its next activation; the cut of the previous focus still applies.
        if (previous != null) {
            openFocusBlock(merged, now);
        }
        return merged;
    }

    private static boolean becomesFocus(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        // A TIME_BLOCK going IN_PROGRESS is focus *accounting*, never a focus that cuts (ADR-039).
        return IN_PROGRESS.equals(merged.status())
            && !AGENDA.equals(merged.type())
            && !TIME_BLOCK.equals(merged.type())
            && (previous == null || !IN_PROGRESS.equals(previous.status()));
    }

    private void cut(FocusCandidate candidate, UUID newFocusId, OffsetDateTime now) {
        Optional<TimeBlockExecutable> activeBlock = timeBlockRepo.findActiveBlockFor(candidate.id());
        OffsetDateTime windowStart = activeBlock.map(TimeBlockExecutable::startTime).orElse(now);
        UUID settledBlockId = activeBlock
            .flatMap(block -> settlementService.settleOnFocusSwitch(block, now))
            .orElse(null);

        SnapshotSubtask snapshot = buildSnapshot(candidate, windowStart, now);
        stateRepo.insertSystemSnapshot(snapshot);
        stateRepo.flagPendingReestimation(candidate.id());

        appendMirrorEvent(snapshot.id(), "ExecutableCreatedEvent", "CREATED", now);
        appendMirrorEvent(candidate.id(), "ExecutableUpdatedEvent", "UPDATED", now);
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, candidate.id().toString(),
            "FocusSwitchedEvent",
            toJson(new FocusSwitchedPayload(
                candidate.userId(), candidate.id(), newFocusId,
                snapshot.id(), settledBlockId, now)),
            SOURCE_SYSTEM, now));
        log.info("Focus switch: {} cut in favor of {} (snapshot {}, settled block {})",
            candidate.id(), newFocusId, snapshot.id(), settledBlockId);
    }

    /**
     * Auto-opens the {@code IN_PROGRESS}/{@code FOCUS} accounting block for the new focus
     * (ADR-039): a {@code TIME_BLOCK} executable riding under the task via {@code parent_id},
     * open-ended ({@code end_time = null}, settled by the next focus switch, never by expiry)
     * and excluded from every mirror by its origin. No outbox event is staged: FOCUS blocks
     * are internal accounting.
     */
    private void openFocusBlock(ExecutableSnapshot focus, OffsetDateTime now) {
        if (timeBlockRepo.findActiveBlockFor(focus.id()).isPresent()) {
            return;
        }
        timeBlockRepo.insert(new TimeBlockExecutable(
            UUID.randomUUID(), focus.userId(), focus.id(), now, null,
            TimeBlockExecutable.STATUS_IN_PROGRESS, TimeBlockExecutable.ORIGIN_FOCUS,
            null, null), focus.name());
    }

    private static SnapshotSubtask buildSnapshot(FocusCandidate candidate,
                                                 OffsetDateTime windowStart, OffsetDateTime now) {
        long minutes = Math.max(java.time.Duration.between(windowStart, now).toMinutes(), 0);
        String description = String.format("[focus] %s -> %s (%d min)", windowStart, now, minutes);
        return new SnapshotSubtask(
            UUID.randomUUID(), candidate.userId(), candidate.id(),
            candidate.name(), description,
            candidate.effortScore(), candidate.isImportant(),
            candidate.energyDrain(), candidate.mentalLoad(), candidate.impact(),
            candidate.estimatedMinutes(), windowStart, now);
    }

    private void appendMirrorEvent(UUID localId, String eventType, String operation,
                                   OffsetDateTime now) {
        String payload = String.format("{\"local_id\":\"%s\",\"operation\":\"%s\"}",
            localId, operation);
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, localId.toString(),
            eventType, payload, SOURCE_SYSTEM, now));
    }

    private String toJson(FocusSwitchedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("FocusSwitchedEvent payload serialization failed", ex);
        }
    }
}
