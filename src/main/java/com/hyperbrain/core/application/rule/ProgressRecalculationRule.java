package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.application.event.SubtaskCompletedPayload;
import com.hyperbrain.core.domain.model.SubtaskCounts;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
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
 * <p>On the transition to DONE it also emits {@code SubtaskCompletedEvent}. The completion clock
 * ({@code last_completed_at}) is owned by {@code CompletionOutcomeRule}, which stamps DONE and FAILED
 * closures alike. Direct writes are no-ops on CREATE (the row is persisted after the rules); the
 * progress itself is still exact.
 *
 * <p>The imputation that used to attribute a completed subtask to the block covering it went with the
 * focus register (ADR-040 D13). It existed to answer how much of the work done had been planned, and
 * that question lost its consumer when time estimation was retired.
 */
@Component
public class ProgressRecalculationRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(ProgressRecalculationRule.class);

    private static final String DONE = "DONE";
    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public ProgressRecalculationRule(
        ExecutableStateRepository stateRepo,
        OutboxRepository outboxRepo,
        ObjectMapper objectMapper
    ) {
        this.stateRepo = stateRepo;
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
        }
        return merged;
    }

    private void onCompleted(ExecutableSnapshot merged, Double parentProgress) {
        // The completion clock (last_completed_at) is stamped by CompletionOutcomeRule, which also
        // covers FAILED closures; this rule keeps the DONE-only progress accounting. The imputation
        // that used to attribute a completed subtask to the block covering it went with the focus
        // register (ADR-040 D13): it existed to answer how much of the work done had been planned, and
        // that question lost its consumer along with the rest of the series.
        OffsetDateTime now = OffsetDateTime.now();
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, merged.id().toString(),
            "SubtaskCompletedEvent",
            toJson(new SubtaskCompletedPayload(
                merged.id(), merged.parentId(), now, parentProgress)),
            SOURCE_SYSTEM, now));
        log.info("Subtask {} of parent {} completed (progress {})",
            merged.id(), merged.parentId(), parentProgress);
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
