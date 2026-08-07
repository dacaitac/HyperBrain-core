package com.hyperbrain.core.application;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.core.domain.model.TimeBlockExecutable;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.core.domain.port.out.TimeBlockExecutableRepository;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Closes {@code TIME_BLOCK} executables whose window has elapsed — the {@code TIME_BLOCK} row of the
 * ADR-040 D4 dispatch table, on its own intraday cadence.
 *
 * <p><b>A block closes as {@code DONE}, not as failed.</b> It is not a commitment that was broken, it
 * is a container of time that elapsed. That is safe because "closed" and "achieved" are separate
 * predicates and every achievement aggregate excludes blocks by construction: the closure-outcome rule
 * skips {@code TIME_BLOCK} outright, the goal's weighted progress filters it out, and the subtask
 * counters behind {@code progress} exclude it. A block that merely elapsed feeds no streak and no
 * success aggregate.
 *
 * <p><b>A block that closes lets its work go.</b> Whatever it still held and nobody finished is retired
 * through the ADR-040 D4 table (Daniel, 2026-08-07): habits close as a sanctioned miss, tasks are
 * rescheduled onto the current day, purchases return to the dateless bag. Leaving them contained would
 * strand them — the container is over, yet the members keep its window stamped on them and no later day
 * admits work dated for a day that has gone.
 *
 * <p><b>What this class no longer does.</b> It used to be the settlement of the focus register: it
 * froze the minutes really spent, stamped the last-execution mark, imputed the subtasks completed
 * inside the window and announced all of it with a published event. ADR-040 D13 retires the register
 * whole, because none of the four questions that series answered has a consumer left — with time
 * estimation gone there is nothing to estimate and therefore nothing to learn. What survived is the
 * completion clock, which the intraday-replan guard reads, and the notification that makes the block's
 * mirrors show it closed.
 *
 * <p>Closing is race-safe: {@code FOR UPDATE SKIP LOCKED} on the read plus the conditional update mean
 * a block closes exactly once even if two runs overlap.
 */
@Service
public class TimeBlockExpiryService {

    private static final Logger log = LoggerFactory.getLogger(TimeBlockExpiryService.class);

    private final TimeBlockExecutableRepository timeBlockRepo;
    private final OutboxRepository outboxRepo;
    private final OverdueSweepService sweepService;
    private final ExecutableStateRepository stateRepo;

    public TimeBlockExpiryService(TimeBlockExecutableRepository timeBlockRepo,
                                  OutboxRepository outboxRepo,
                                  OverdueSweepService sweepService,
                                  ExecutableStateRepository stateRepo) {
        this.timeBlockRepo = timeBlockRepo;
        this.outboxRepo = outboxRepo;
        this.sweepService = sweepService;
        this.stateRepo = stateRepo;
    }

    /**
     * Closes every open block whose {@code end_time} has passed.
     *
     * @param now the expiry boundary
     * @return how many blocks this run closed
     */
    @Transactional
    public int expireDueBlocks(OffsetDateTime now) {
        int closed = 0;
        for (TimeBlockExecutable block : timeBlockRepo.lockOpenExpired(now)) {
            if (!timeBlockRepo.settle(
                block.id(), TimeBlockExecutable.STATUS_DONE, null, block.endTime())) {
                log.debug("Block {} no longer open; closing skipped", block.id());
                continue;
            }
            // A block that closes lets its unfinished work go, by the same table the sweep applies —
            // otherwise the members stay contained in a container that is over, carrying its window as
            // a hard copy, and no later day will ever admit them again (Daniel, 2026-08-07). The
            // release is invoked here rather than in the domain chain because this closure never
            // passes through it: it is a direct write.
            sweepService.releaseMembersOf(block.id(), now, stateRepo.findUserZone(block.userId()));
            stageSatelliteMirror(block);
            closed++;
            log.info("Block {} elapsed and was closed as done", block.id());
        }
        return closed;
    }

    /**
     * Stages the satellite notification of a closed block, so its mirrors stop showing it as open.
     *
     * <p>This exists because the block's closure once reached no propagator at all and its Notion page
     * read "Not started" forever. Since a block is an executable, the standard notification is all its
     * mirrors need: the Apple propagator keeps a closed block's calendar event alive instead of
     * deleting it, and the destination calendar stays type-routed.
     *
     * <p>No reopening by echo: a calendar event has no completed flag, so the inbound merge preserves
     * the domain status, and Notion's closed projection is read back as an echo that changes nothing.
     *
     * <p>Focus-origin rows are excluded. They are internal accounting that was never mirrored and holds
     * no satellite mapping, so notifying one would not update an entity — it would create a spurious
     * calendar event for a block the user never asked for. Nothing produces them any more; the guard
     * stands for the ones production already holds.
     */
    private void stageSatelliteMirror(TimeBlockExecutable block) {
        if (TimeBlockExecutable.ORIGIN_FOCUS.equals(block.origin())) {
            log.debug("Block {} is legacy focus accounting; its closure is not mirrored", block.id());
            return;
        }
        outboxRepo.append(ExecutableOutboxEvents.updated(block.id()));
    }
}
