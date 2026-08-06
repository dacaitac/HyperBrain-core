package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.application.event.SubtaskCompletedPayload;
import com.hyperbrain.core.domain.model.SubtaskCounts;
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
import java.util.Objects;
import java.util.UUID;

/**
 * DR-07 (ADR-013 D4) — the user subtask is the atomic unit of progress.
 *
 * <p>On any status change of a user subtask (never a system-generated snapshot), recomputes
 * the parent's materialized {@code progress} as {@code user DONE / user total} — null when the
 * parent has no user subtasks. The counters exclude the row being ingested and add its
 * in-memory state instead, so a subtask arriving already-DONE on CREATE still counts.
 *
 * <p>On the transition to DONE it additionally imputes the subtask eagerly to the parent's
 * executing block when one covers the completion (via {@code imputed_block_id}, ADR-039), and
 * emits {@code SubtaskCompletedEvent}. Un-completing reverts the imputation. The completion
 * clock ({@code last_completed_at}) is owned by {@code CompletionOutcomeRule}, which stamps
 * DONE and FAILED closures alike. Direct writes are no-ops on CREATE (the row is persisted
 * after the rules); the progress itself is still exact.
 */
@Component
public class ProgressRecalculationRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(ProgressRecalculationRule.class);

    private static final String DONE = "DONE";
    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final ExecutableStateRepository stateRepo;
    private final TimeBlockExecutableRepository timeBlockRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public ProgressRecalculationRule(
        ExecutableStateRepository stateRepo,
        TimeBlockExecutableRepository timeBlockRepo,
        OutboxRepository outboxRepo,
        ObjectMapper objectMapper
    ) {
        this.stateRepo = stateRepo;
        this.timeBlockRepo = timeBlockRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.parentId() == null || !statusChanged(previous, merged)
            || stateRepo.isSystemGenerated(merged.id())) {
            return merged;
        }

        SubtaskCounts persisted = stateRepo.countUserSubtasks(merged.parentId(), merged.id());
        SubtaskCounts counts = new SubtaskCounts(
            persisted.total() + 1,
            persisted.done() + (DONE.equals(merged.status()) ? 1 : 0));
        stateRepo.updateProgress(merged.parentId(), counts.progress());

        if (becameDone(previous, merged)) {
            onCompleted(merged, counts.progress());
        } else if (becameUndone(previous, merged)) {
            stateRepo.clearImputation(merged.id());
        }
        return merged;
    }

    private void onCompleted(ExecutableSnapshot merged, Double parentProgress) {
        // The completion clock (last_completed_at) is stamped by CompletionOutcomeRule
        // (ADR-039), which also covers FAILED closures; this rule keeps the DONE-only
        // imputation and progress accounting.
        OffsetDateTime now = OffsetDateTime.now();
        UUID imputedBlockId = timeBlockRepo.findActiveBlockFor(merged.parentId())
            .map(TimeBlockExecutable::id)
            .orElse(null);
        if (imputedBlockId != null) {
            stateRepo.imputeToBlock(merged.id(), imputedBlockId);
        }
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, merged.id().toString(),
            "SubtaskCompletedEvent",
            toJson(new SubtaskCompletedPayload(
                merged.id(), merged.parentId(), now, imputedBlockId, parentProgress)),
            SOURCE_SYSTEM, now));
        log.info("Subtask {} of parent {} completed (progress {}, imputed block {})",
            merged.id(), merged.parentId(), parentProgress, imputedBlockId);
    }

    private static boolean statusChanged(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return previous == null || !Objects.equals(previous.status(), merged.status());
    }

    private static boolean becameDone(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return DONE.equals(merged.status())
            && (previous == null || !DONE.equals(previous.status()));
    }

    private static boolean becameUndone(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return previous != null && DONE.equals(previous.status())
            && !DONE.equals(merged.status());
    }

    private String toJson(SubtaskCompletedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("SubtaskCompletedEvent payload serialization failed", ex);
        }
    }
}
