package com.hyperbrain.core.application;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.core.application.rule.CompletionOutcomeRule;
import com.hyperbrain.core.application.rule.RecurrenceCloneRule;
import com.hyperbrain.core.domain.model.CompletionState;
import com.hyperbrain.core.domain.model.OverduePolicy;
import com.hyperbrain.core.domain.model.OverdueSweepReport;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/*
 * Design pattern: Domain Service driving a table-driven dispatch, reusing the rule chain's links
 * as policies with a second caller.
 * Reason: ADR-040 D11 establishes that a rule which two callers need becomes one implementation
 * with two callers, never a copy. The day-close closure needs exactly what the ingestion chain
 * does on a closure — the completion clock, the streak outcome and the recurrence clone — so it
 * invokes those two links directly instead of re-deriving them. What it does NOT do is run the
 * whole chain: the containment hard copy would re-assert yesterday's block window onto the very
 * row this sweep is moving out of yesterday.
 */

/**
 * ADR-040 D4 — the retirement of work that is not going to happen, dispatched by type.
 *
 * <p><b>Why this runs before the window model.</b> The admission rule only lets the target day's
 * items and the dateless ones into a day (ADR-040 D3). Without this an item left undone stays stuck
 * in the past and never becomes a candidate again. This is what leaves the bag the next run draws
 * from clean.
 *
 * <p><b>Two selectors, one policy</b> (Daniel, 2026-08-07). What is retired is chosen in two
 * independent ways, and both hand the same candidates to the same table:
 * <ul>
 *   <li><b>By time</b> ({@link #sweep}) — everything whose window closed behind the instant the run
 *       fired at. It fires at day close <em>and</em> on every replan, before the generation, so the
 *       day is planned over a bag that is already clean. The bound is an instant and not a day
 *       precisely so that an afternoon replan reaches this morning's leftovers.</li>
 *   <li><b>By membership</b> ({@link #releaseMembersOf}) — the uncompleted members of a block that
 *       just closed, whoever closed it. A member is retired <em>because its block closed</em>, not
 *       because an hour went by: a block closed by hand while its window is still ahead must let its
 *       work go all the same, and no temporal predicate would have caught that.</li>
 * </ul>
 *
 * <p><b>What each type gets</b> is {@link OverduePolicy}'s table. Three behaviours implement it:
 * <ul>
 *   <li><b>Close as a sanctioned miss</b> ({@code HABIT}, {@code LEAD_MEASURE}, {@code ACTIVITY},
 *       {@code AGENDA}) — the conditional UPDATE is the race guard; then the completion clock and
 *       the streak reset (DR-08 outcome rule) and the recurrence clone (DR-04), in the chain's own
 *       order so the clone copies the already-reset streak. DR-04 clones any of these types alike
 *       when it carries a frequency — {@code AGENDA} included, whether the closure came from this
 *       sweep or from a human closing it in Notion.</li>
 *   <li><b>Re-date</b> ({@code TASK}) — the only type that moves instead of closing. Same row, new
 *       day; see {@link #reschedule} for the order of operations.</li>
 *   <li><b>Clear the date</b> ({@code BUYING}) — back to the dateless bag, from where it can enter
 *       any day. A shopping list has no day.</li>
 * </ul>
 *
 * <p><b>Idempotence</b> is a property of every write, not of a marker column: the candidate query
 * only sees open rows, the closure UPDATE is conditional on the row still being open, and the window
 * UPDATE is conditional on the value actually differing. Running twice moves nothing and emits
 * nothing the second time — and that is also what absorbs the two selectors overlapping, which they
 * routinely do: a member of a block that just closed usually also sits behind the trigger hour,
 * because its date is a hard copy of the block's window. Whichever reaches it first does the work;
 * the other writes nothing and announces nothing.
 *
 * <p><b>Satellites.</b> Every row the sweep touches gets one {@code ExecutableUpdatedEvent} with a
 * {@code SYSTEM} origin, so Apple and Notion both re-project it (the propagators re-read the row and
 * mirror it whole). Nothing here deletes anything: a closed reminder is checked off, never removed.
 */
@Service
public class OverdueSweepService {

    private static final Logger log = LoggerFactory.getLogger(OverdueSweepService.class);

    private final ExecutableStateRepository stateRepo;
    private final CompletionOutcomeRule completionOutcomeRule;
    private final RecurrenceCloneRule recurrenceCloneRule;
    private final OutboxRepository outboxRepo;

    public OverdueSweepService(
        ExecutableStateRepository stateRepo,
        CompletionOutcomeRule completionOutcomeRule,
        RecurrenceCloneRule recurrenceCloneRule,
        OutboxRepository outboxRepo
    ) {
        this.stateRepo = stateRepo;
        this.completionOutcomeRule = completionOutcomeRule;
        this.recurrenceCloneRule = recurrenceCloneRule;
        this.outboxRepo = outboxRepo;
    }

    /**
     * Sweeps everything of the user whose window closed before {@code referenceInstant}. One
     * transaction for the whole run: the domain writes and their outbox rows commit together or not
     * at all (Transactional Outbox), and the volume is a handful of rows.
     *
     * <p><b>The re-dating target is the instant's own day.</b> A sweep that fires at four in the
     * afternoon puts this morning's leftovers back on <em>today</em>, not on tomorrow, so the
     * generation that runs behind it can place them in the windows the day has left. At the day-close
     * run the same rule reads as "the next day", because the instant already belongs to it.
     *
     * @param userId           the owning user
     * @param referenceInstant the instant the sweep fired at — everything closed behind it has
     *                         expired, and retired work lands on the local day this instant falls in
     * @param zone             the timezone the local day is reasoned in
     * @return what the run did, per behaviour
     */
    @Transactional
    public OverdueSweepReport sweep(UUID userId, OffsetDateTime referenceInstant, ZoneId zone) {
        LocalDate referenceDay = referenceInstant.atZoneSameInstant(zone).toLocalDate();
        OverdueSweepReport report = retire(
            stateRepo.findOverdue(userId, OverduePolicy.sweptTypes(), referenceInstant, zone),
            referenceDay, zone);
        if (!report.isEmpty()) {
            log.info("Sweep at {} closed {} as FAILED, re-dated {} item(s) onto {} and cleared the "
                    + "date of {} purchase(s)",
                referenceInstant, report.closedAsFailed(), report.rescheduled(), referenceDay,
                report.dateCleared());
        }
        return report;
    }

    /**
     * Applies the same table to the uncompleted members of a block that has just closed, whether a
     * human closed it in Notion or the elapsed-window settlement did (Daniel, 2026-08-07). Without
     * this, closing a block strands its unfinished work inside it: the members keep the block's
     * window stamped on them by the hard copy, and since a day only admits what is dated for it, they
     * never become candidates again.
     *
     * <p><b>Selected by membership, deliberately.</b> Whether a member's own hour has gone by is not
     * asked: it is being retired because the container it lived in is over.
     *
     * <p>Two exclusions mirror the temporal selector's, for the same reasons: a system-generated row
     * is not the user's work, and a row with a parent moves with its parent (DR-10), never on its own.
     *
     * @param blockId          the block that closed; never null
     * @param referenceInstant the instant the closure was observed at — retired work lands on its day
     * @param zone             the timezone the local day is reasoned in
     * @return what the release did, per behaviour
     */
    @Transactional
    public OverdueSweepReport releaseMembersOf(UUID blockId, OffsetDateTime referenceInstant,
                                               ZoneId zone) {
        List<ExecutableSnapshot> members = stateRepo.findContainedBy(blockId).stream()
            .filter(member -> !member.systemGenerated())
            .filter(member -> member.parentId() == null)
            .filter(member -> !CompletionState.isClosed(member.status()))
            .toList();
        OverdueSweepReport report =
            retire(members, referenceInstant.atZoneSameInstant(zone).toLocalDate(), zone);
        if (!report.isEmpty()) {
            log.info("Block {} closed with unfinished work: {} closed as FAILED, {} re-dated, {} sent "
                + "back to the dateless bag", blockId, report.closedAsFailed(), report.rescheduled(),
                report.dateCleared());
        }
        return report;
    }

    /** The one dispatch both selectors feed: ADR-040 D4's table, applied row by row. */
    private OverdueSweepReport retire(List<ExecutableSnapshot> candidates, LocalDate referenceDay,
                                      ZoneId zone) {
        int closed = 0;
        int rescheduled = 0;
        int dateCleared = 0;
        for (ExecutableSnapshot candidate : candidates) {
            switch (OverduePolicy.actionFor(candidate.type())) {
                case CLOSE_AS_FAILED -> closed += closeAsFailed(candidate) ? 1 : 0;
                case RESCHEDULE -> rescheduled += reschedule(candidate, referenceDay, zone) ? 1 : 0;
                case CLEAR_DATE -> dateCleared += clearDate(candidate) ? 1 : 0;
                case NONE -> { /* the type is outside the table: LEARNING_SESSION, a nested block */ }
            }
        }
        return new OverdueSweepReport(closed, rescheduled, dateCleared);
    }

    // ── Behaviours ────────────────────────────────────────────────────────────

    private boolean closeAsFailed(ExecutableSnapshot candidate) {
        if (!stateRepo.closeAsFailed(candidate.id())) {
            log.debug("Executable {} is no longer open; day-close skipped", candidate.id());
            return false;
        }
        ExecutableSnapshot failed = withStatus(candidate, "FAILED");
        completionOutcomeRule.apply(candidate, failed, ExternalSystem.SYSTEM);
        recurrenceCloneRule.apply(candidate, failed, ExternalSystem.SYSTEM);
        outboxRepo.append(ExecutableOutboxEvents.updated(candidate.id()));
        return true;
    }

    /**
     * Moves an expired {@code TASK} onto the reference day. The order of operations is the whole
     * point, and it is this one:
     *
     * <ol>
     *   <li><b>Detach first.</b> A task that sat inside yesterday's block carries a date that is a
     *       SYSTEM-owned hard copy of that block's window (DR-10). Writing the new date while the
     *       containment still stands would let the hard copy re-stamp yesterday's window back on
     *       the next ingestion — the self-reasserting date corruption DR-04 already had to defend
     *       against once. So the containment goes first, and a re-dated task never keeps
     *       yesterday's block.</li>
     *   <li><b>Then the window.</b> A task that <em>was</em> contained gets the midnight placeholder
     *       of the new day: the hour it carried was the block's, not the user's, and a retirement
     *       decided by the system returns the hour to the placeholder while the day survives
     *       (ADR-040 D10). A task that was <em>not</em> contained keeps its own time of day — that
     *       hour is the user's and only its date shifts, by the exact number of days that separates
     *       it from the reference day (a five-day-stale task lands on the reference day too, which
     *       is what makes a second run a no-op).</li>
     *   <li><b>Then the descendants.</b> The subtasks under it hold the same hard copy, so the new
     *       window is pushed down the transitive chain through the very same DR-10 write — value
     *       idempotent, one event per actually-changed child, none for the rest.</li>
     * </ol>
     *
     * <p>The cycle is deliberately left alone: a null cycle carries no signal and clearing an
     * inherited one would destroy alignment data on every daily plan.
     */
    private boolean reschedule(ExecutableSnapshot candidate, LocalDate referenceDay, ZoneId zone) {
        boolean wasContained = stateRepo.clearContainer(candidate.id());
        OffsetDateTime start;
        OffsetDateTime end;
        if (wasContained) {
            start = referenceDay.atStartOfDay(zone).toOffsetDateTime();
            end = null;
        } else {
            long days = daysUntil(candidate.startTime(), referenceDay, zone);
            start = candidate.startTime().plusDays(days);
            end = candidate.endTime() != null ? candidate.endTime().plusDays(days) : null;
        }
        if (!stateRepo.reschedule(candidate.id(), start, end)) {
            log.debug("Task {} was already on {}; re-dating skipped", candidate.id(), referenceDay);
            return false;
        }
        for (UUID childId : stateRepo.copyScheduleToContained(
            candidate.id(), start, end, candidate.cycleId())) {
            outboxRepo.append(ExecutableOutboxEvents.updated(childId));
        }
        outboxRepo.append(ExecutableOutboxEvents.updated(candidate.id()));
        log.info("Task {} expired and was re-dated to {} (detached from its block: {})",
            candidate.id(), referenceDay, wasContained);
        return true;
    }

    /**
     * Sends an expired purchase back to the dateless bag. The containment goes first for the same
     * reason it does when re-dating a task: a purchase left inside yesterday's block still carries
     * that block as its scheduling authority, and the hard copy would stamp the block's window
     * straight back onto the date this just cleared.
     */
    private boolean clearDate(ExecutableSnapshot candidate) {
        stateRepo.clearContainer(candidate.id());
        if (!stateRepo.reschedule(candidate.id(), null, null)) {
            return false;
        }
        outboxRepo.append(ExecutableOutboxEvents.updated(candidate.id()));
        log.info("Purchase {} expired; date cleared — back to the dateless bag", candidate.id());
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Whole local days from an instant's day to the reference day. Computed on local dates (not on
     * the instants) so the shift is a calendar-day shift and the time of day survives it verbatim.
     */
    private static long daysUntil(OffsetDateTime from, LocalDate referenceDay, ZoneId zone) {
        return ChronoUnit.DAYS.between(from.atZoneSameInstant(zone).toLocalDate(), referenceDay);
    }

    private static ExecutableSnapshot withStatus(ExecutableSnapshot s, String status) {
        return new ExecutableSnapshot(
            s.id(), s.userId(), s.parentId(), s.cycleId(),
            s.name(), s.description(), s.type(), status,
            s.priorityScore(), s.urgencyScore(), s.effortScore(),
            s.isImportant(), s.frequency(),
            s.startTime(), s.endTime(), s.sourceCalendar(),
            s.energyDrain(), s.mentalLoad(), s.impact(),
            s.systemGenerated(), s.containerBlockId());
    }
}
