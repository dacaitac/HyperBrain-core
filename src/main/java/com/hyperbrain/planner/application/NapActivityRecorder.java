package com.hyperbrain.planner.application;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/*
 * Design pattern: Application-layer coordinator with an idempotent upsert keyed on a natural key.
 * Reason: a nap has no external identifier — the dump is re-sent on every replan of the day and the
 * watch revises its staging, so the same episode arrives many times under no id at all. The window it
 * happened in is the only stable key it has, which is why the decision "is this the nap I already
 * recorded, or a new one" belongs here, next to the two repository calls that answer it, rather than
 * inside a single opaque SQL upsert.
 */

/**
 * Records the naps of a sleep day as completed activities, so a nap the user actually took shows up in
 * Notion and in his calendar as what it was: time that went to sleeping (Daniel, 2026-08-08).
 *
 * <p><b>Which sessions are naps.</b> The sleep day's main session is the night — it is the longest one
 * and it is what gives the row its hours. Every other session of the same day is a nap. The night is
 * not recorded: it is already the {@code tel_sleep_record} row, and writing it again as an activity
 * would duplicate it as a calendar event covering the whole night.
 *
 * <p><b>Idempotency is the point, not a nicety.</b> Recording twice would leave two activities and two
 * calendar events for one nap, and the replan that re-sends the dump runs several times a day. An
 * episode that overlaps one already recorded under the sleep cycle is therefore treated as the same
 * episode <em>revised</em>: its end is moved and nothing is inserted.
 *
 * <p>The write goes through {@link PlannerStateRepository} and the Transactional Outbox, exactly like
 * the planner's blocks — the satellites are reached by the standard propagators, never directly.
 */
@Component
public class NapActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(NapActivityRecorder.class);

    /**
     * The user's cycle a nap belongs to. Looked up by name rather than by id: the cycle is the user's
     * own and its id differs per environment, so an id here would be a production constant compiled
     * into the domain.
     */
    public static final String SLEEP_CYCLE_NAME = "Sueño";

    /**
     * The title a recorded nap carries. In Spanish for the same reason the day template's labels are:
     * it is the title of an event in Daniel's calendar and of a page in his Notion, not a piece of code.
     */
    public static final String NAP_TITLE = "Siesta";

    /**
     * The least sleep an episode must hold to be a nap worth recording (Daniel's criterion). Below a
     * quarter of an hour the watch is reporting a doze, a mis-staged stretch of stillness or the tail of
     * a night it split in two — none of which is an activity the day should show, and each of which
     * would put a piece of clutter in the calendar that Daniel then has to delete by hand.
     */
    private static final Duration NAP_FLOOR = Duration.ofMinutes(15);

    private final PlannerStateRepository repository;
    private final OutboxRepository outboxRepository;

    public NapActivityRecorder(PlannerStateRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Records every nap of the sleep day, inserting the ones not seen before and re-timing the ones
     * already there.
     *
     * <p>A user with no «{@value #SLEEP_CYCLE_NAME}» cycle is logged at WARN and skipped: the cycle is
     * the user's own classification of his life and the system does not get to invent one for him.
     *
     * @param userId the owning user; never null
     * @param sleep  the sleep day whose sessions are being recorded; never null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID userId, AggregatedSleep sleep) {
        List<SleepSession> naps = napsOf(sleep);
        if (naps.isEmpty()) {
            return;
        }
        Optional<UUID> cycleId = repository.findCycleIdByName(userId, SLEEP_CYCLE_NAME);
        if (cycleId.isEmpty()) {
            log.warn("{} nap(s) for user {} not recorded: no cycle named \"{}\"",
                naps.size(), userId, SLEEP_CYCLE_NAME);
            return;
        }
        for (SleepSession nap : naps) {
            record(userId, cycleId.get(), nap);
        }
    }

    /** Inserts the nap, or re-times the already-recorded episode its window overlaps. */
    private void record(UUID userId, UUID cycleId, SleepSession nap) {
        Optional<UUID> recorded =
            repository.findActivityOverlapping(userId, cycleId, nap.start(), nap.end());
        if (recorded.isPresent()) {
            if (repository.retimeActivityEnd(recorded.get(), nap.end())) {
                outboxRepository.append(ExecutableOutboxEvents.updated(recorded.get()));
                log.info("Nap {} re-timed to end at {} (the watch revised it)", recorded.get(), nap.end());
            }
            return;
        }
        UUID activityId = UUID.randomUUID();
        repository.insertCompletedActivity(
            activityId, userId, cycleId, NAP_TITLE, nap.start(), nap.end());
        outboxRepository.append(ExecutableOutboxEvents.created(activityId));
        log.info("Nap {} recorded for user {}: {} .. {}", activityId, userId, nap.start(), nap.end());
    }

    /** The day's sessions that are naps: everything but the night, that slept long enough to count. */
    private static List<SleepSession> napsOf(AggregatedSleep sleep) {
        List<SleepSession> naps = new ArrayList<>();
        for (SleepSession session : sleep.sessions()) {
            if (!session.equals(sleep.mainSession())
                && session.asleepSeconds() >= NAP_FLOOR.toSeconds()) {
                naps.add(session);
            }
        }
        return naps;
    }
}
