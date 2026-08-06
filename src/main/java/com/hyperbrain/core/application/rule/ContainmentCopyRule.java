package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/*
 * Design pattern: Chain of Responsibility link
 * Reason: plugs the ADR-039 hard-copy rule into the DomainChangeProcessor chain; the side
 * effects (batch child UPDATE + one outbox event per affected child) run inside the caller's
 * ingestion transaction so the copies are always consistent with their container.
 */

/**
 * ADR-039 hard-copy rule: a contained executable — a member of a {@code TIME_BLOCK} via
 * {@code container_block_id}, or a child under a parent via {@code parent_id}, transitively
 * (subtask → task → block) — carries a SYSTEM-owned copy of its container's {@code start_time}
 * / {@code end_time} and {@code cycle_id} (ADR-012 D1 amendment). Two directions, both
 * idempotent by value (equal ⇒ absolute no-op, no outbox — the echo must converge):
 *
 * <ul>
 *   <li><b>Child side (assert / reassert).</b> When the ingested row is contained, its date
 *       and cycle are rewritten to the container-derived values before persisting. An inbound
 *       human edit that tried to move them is re-asserted with a visible {@code Sync Note}
 *       ("the container owns the date; move it or detach"). Detaching (clearing the container
 *       or parent) keeps the copied values — they persist by design.</li>
 *   <li><b>Container side (propagate).</b> When the ingested row's schedule or cycle changed,
 *       the copy is pushed to every transitive descendant in one batch UPDATE, with one
 *       {@code ExecutableUpdatedEvent} staged per actually-changed child so mirrors re-project
 *       (the urgency/priority rescore rides the existing on-event path, ADR-020).</li>
 * </ul>
 *
 * <p>The copy honours DR-01 per child (reminder types receive no {@code end_time}) and a null
 * container cycle carries no signal (children keep their own cycle) — copying a null cycle
 * would destroy alignment data on every daily plan. {@code TIME_BLOCK} rows never receive
 * copies: a container owns its own window.
 */
@Component
public class ContainmentCopyRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(ContainmentCopyRule.class);

    private static final String TIME_BLOCK = "TIME_BLOCK";
    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final String SOURCE_SYSTEM = "SYSTEM";
    private static final Set<String> EVENT_TYPES = Set.of("ACTIVITY", "AGENDA", "LEARNING_SESSION");

    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public ContainmentCopyRule(ExecutableStateRepository stateRepo, OutboxRepository outboxRepo,
                               ObjectMapper objectMapper) {
        this.stateRepo = stateRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.systemGenerated()) {
            return merged;
        }
        ExecutableSnapshot state = TIME_BLOCK.equals(merged.type())
            ? merged
            : assertContainedSchedule(previous, merged, origin);
        propagateToContained(previous, state);
        return state;
    }

    // ── Child side ────────────────────────────────────────────────────────────

    private ExecutableSnapshot assertContainedSchedule(ExecutableSnapshot previous,
                                                       ExecutableSnapshot merged,
                                                       ExternalSystem origin) {
        Optional<ContainerSchedule> authority =
            stateRepo.findContainerSchedule(merged.id(), merged.parentId());
        if (authority.isEmpty() || !authority.get().imposesSchedule()) {
            return merged;
        }
        ContainerSchedule container = authority.get();
        OffsetDateTime assertedStart = container.startTime();
        OffsetDateTime assertedEnd =
            EVENT_TYPES.contains(merged.type()) ? container.endTime() : null;
        UUID assertedCycle = container.cycleId() != null ? container.cycleId() : merged.cycleId();
        if (sameInstant(merged.startTime(), assertedStart)
            && sameInstant(merged.endTime(), assertedEnd)
            && Objects.equals(merged.cycleId(), assertedCycle)) {
            return merged;
        }
        boolean humanMove = isHumanSource(origin) && previous != null
            && scheduleMoved(previous, merged);
        ExecutableSnapshot asserted = withSchedule(merged, assertedStart, assertedEnd, assertedCycle);
        if (humanMove) {
            stageReassertion(asserted.id(), container.containerName());
            log.info("Contained executable {}: inbound {} edit of date/cycle re-asserted to "
                + "container {} (ADR-039)", asserted.id(), origin, container.containerId());
        }
        return asserted;
    }

    // ── Container side ────────────────────────────────────────────────────────

    private void propagateToContained(ExecutableSnapshot previous, ExecutableSnapshot state) {
        if (previous == null || !scheduleMoved(previous, state)) {
            return;
        }
        List<UUID> changed = stateRepo.copyScheduleToContained(
            state.id(), state.startTime(), state.endTime(), state.cycleId());
        for (UUID childId : changed) {
            stageChildUpdate(childId);
        }
        if (!changed.isEmpty()) {
            log.info("Container {} schedule change hard-copied to {} contained executable(s)",
                state.id(), changed.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean scheduleMoved(ExecutableSnapshot before, ExecutableSnapshot after) {
        return !sameInstant(before.startTime(), after.startTime())
            || !sameInstant(before.endTime(), after.endTime())
            || !Objects.equals(before.cycleId(), after.cycleId());
    }

    private static boolean isHumanSource(ExternalSystem origin) {
        return origin == ExternalSystem.APPLE || origin == ExternalSystem.NOTION;
    }

    private static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.isEqual(b);
    }

    private static ExecutableSnapshot withSchedule(ExecutableSnapshot s, OffsetDateTime start,
                                                   OffsetDateTime end, UUID cycleId) {
        return new ExecutableSnapshot(
            s.id(), s.userId(), s.parentId(), cycleId,
            s.name(), s.description(), s.type(), s.status(),
            s.priorityScore(), s.urgencyScore(), s.effortScore(),
            s.isImportant(), s.frequency(),
            start, end, s.sourceCalendar(),
            s.energyDrain(), s.mentalLoad(), s.impact(),
            s.systemGenerated());
    }

    private void stageChildUpdate(UUID childId) {
        String payload = String.format("{\"local_id\":\"%s\",\"operation\":\"UPDATED\"}", childId);
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, childId.toString(),
            "ExecutableUpdatedEvent", payload, SOURCE_SYSTEM, OffsetDateTime.now()));
    }

    /**
     * Stages the visible re-assertion: a SYSTEM CANONICAL_STATE reflection whose payload
     * carries the {@code Sync Note} text the Notion propagator writes onto the page (field
     * scoped, so the human-edit guard never discards it).
     */
    private void stageReassertion(UUID executableId, String containerName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("local_id", executableId.toString());
        payload.put("operation", "UPDATED");
        payload.put("reflection", "CANONICAL_STATE");
        payload.put("sync_note",
            "Schedule is owned by '" + containerName + "'; move that container or remove the "
                + "containment to reschedule this item");
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), EXECUTABLE_AGGREGATE, executableId.toString(),
            "ExecutableUpdatedEvent", toJson(payload), SOURCE_SYSTEM, OffsetDateTime.now()));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Containment reassertion payload serialization failed", ex);
        }
    }
}
