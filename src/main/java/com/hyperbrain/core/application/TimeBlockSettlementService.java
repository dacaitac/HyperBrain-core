package com.hyperbrain.core.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.application.event.TimeBlockSettledPayload;
import com.hyperbrain.core.domain.model.TimeBlockExecutable;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.core.domain.port.out.TimeBlockExecutableRepository;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Settles {@code TIME_BLOCK} executables (DR-08, ADR-013 as amended by ADR-039): freezes
 * {@code actual_duration_minutes} and {@code last_completed_at}, imputes the user subtasks
 * completed inside the block window via {@code imputed_block_id} and emits a
 * {@code TimeBlockSettledEvent} through the Transactional Outbox. Shared by the focus-switch
 * rule ({@code DONE}, inside the ingestion transaction) and the expiry scheduler
 * ({@code FAILED}, own transaction). Settlement is race-safe: the conditional UPDATE plus
 * {@code FOR UPDATE SKIP LOCKED} on the expiry path guarantee a block settles exactly once.
 */
@Service
public class TimeBlockSettlementService {

    private static final Logger log = LoggerFactory.getLogger(TimeBlockSettlementService.class);

    private static final String AGGREGATE_TYPE = "CORE_TIME_BLOCK";
    private static final String EVENT_TYPE = "TimeBlockSettledEvent";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final TimeBlockExecutableRepository timeBlockRepo;
    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public TimeBlockSettlementService(
        TimeBlockExecutableRepository timeBlockRepo,
        ExecutableStateRepository stateRepo,
        OutboxRepository outboxRepo,
        ObjectMapper objectMapper
    ) {
        this.timeBlockRepo = timeBlockRepo;
        this.stateRepo = stateRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Settles the executing block of a task cut by a focus switch (DR-05 → DR-08) as
     * {@code DONE}. Joins the caller's ingestion transaction. Gross minutes only: the AGENDA
     * clock suspension is deferred to HU-02.
     *
     * @param block the executing block of the cut task
     * @param now   the cut instant (window end)
     * @return the settled block id, or empty if a concurrent settlement won the race
     */
    public Optional<UUID> settleOnFocusSwitch(TimeBlockExecutable block, OffsetDateTime now) {
        int actual = grossMinutes(block.startTime(), now);
        return settleInternal(block, TimeBlockExecutable.STATUS_DONE, now, actual)
            ? Optional.of(block.id())
            : Optional.empty();
    }

    /**
     * Expires every open block whose {@code end_time} passed (DR-08 cron path) as
     * {@code FAILED}. Blocks that were never executed (still {@code PLANNED}) settle with a
     * null actual duration: the honest datum is "nothing observed". {@code last_completed_at}
     * is stamped on FAILED too (ADR-039 matrix: the intraday-replan guard needs the clock even
     * on a sanctioned miss). No overrun event is emitted here — that is HU-02.
     *
     * @param now the expiry boundary
     * @return how many blocks were settled by this run
     */
    @Transactional
    public int expireDueBlocks(OffsetDateTime now) {
        int settled = 0;
        for (TimeBlockExecutable block : timeBlockRepo.lockOpenExpired(now)) {
            Integer actual = TimeBlockExecutable.STATUS_IN_PROGRESS.equals(block.status())
                ? grossMinutes(block.startTime(), block.endTime())
                : null;
            if (settleInternal(block, TimeBlockExecutable.STATUS_FAILED, block.endTime(), actual)) {
                settled++;
            }
        }
        return settled;
    }

    private boolean settleInternal(TimeBlockExecutable block, String finalStatus,
                                   OffsetDateTime windowEnd, Integer actualDurationMinutes) {
        OffsetDateTime settledAt = OffsetDateTime.now();
        if (!timeBlockRepo.settle(block.id(), finalStatus, actualDurationMinutes, settledAt)) {
            log.debug("Block {} no longer open; settlement as {} skipped", block.id(), finalStatus);
            return false;
        }
        int imputed = stateRepo.imputeCompletedSubtasks(
            block.id(), block.startTime(), windowEnd);
        outboxRepo.append(new OutboxEvent(
            UUID.randomUUID(), AGGREGATE_TYPE, block.id().toString(), EVENT_TYPE,
            toJson(new TimeBlockSettledPayload(
                block.id(), block.anchorTaskId(), finalStatus,
                block.startTime(), block.endTime(), block.plannedMinutes(),
                actualDurationMinutes, imputed)),
            SOURCE_SYSTEM, settledAt));
        log.info("Block {} settled as {} (actual {} min, {} subtasks imputed)",
            block.id(), finalStatus, actualDurationMinutes, imputed);
        return true;
    }

    private String toJson(TimeBlockSettledPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("TimeBlockSettledEvent payload serialization failed", ex);
        }
    }

    private static int grossMinutes(OffsetDateTime start, OffsetDateTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        return (int) Math.max(minutes, 0);
    }
}
