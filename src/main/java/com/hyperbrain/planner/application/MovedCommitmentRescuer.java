package com.hyperbrain.planner.application;

import com.hyperbrain.core.domain.port.in.ExecutableSchedulingService;
import com.hyperbrain.planner.domain.model.MovableCommitment;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/*
 * Design pattern: Application-layer coordinator with a single, narrow trigger.
 * Reason: this is the one place where the planner writes onto something the USER created in his own
 * calendar, so the trigger is deliberately the narrowest one that serves the case ADR-040 D9 argues
 * for — and nothing wider. Keeping it in its own class is what makes that narrowness legible.
 */

/**
 * Rescues the commitments an interruption ran over: the middle tier of the rigidity hierarchy
 * (ADR-040 D9).
 *
 * <p>Activities and study sessions carry a calendar window of their own, so they are never members of a
 * block. What they can lose is their hour. The case Daniel described is precise — something interrupted
 * him right before one was due to start — and so is the response: <b>on a replan, a commitment of the
 * day whose window has already gone by is moved forward to the first stretch of the same day that is
 * free.</b>
 *
 * <p><b>Everything about this is deliberately narrow.</b> It runs only on a replan, only for a
 * commitment whose window is already behind the run's lower edge, and only within the same local day —
 * the day is the user's, and {@code core} refuses a write that would change it rather than trusting a
 * caller not to try. A commitment still ahead is never touched, however inconvenient its hour: the
 * planner recovering authority over the hour is not a licence to rearrange a calendar the user wrote.
 *
 * <p>A commitment with nowhere left to go on its day <b>stays where it is</b>. Dropping it or pushing
 * it to tomorrow would be the planner deciding something only the user may.
 */
@Component
public class MovedCommitmentRescuer {

    private static final Logger log = LoggerFactory.getLogger(MovedCommitmentRescuer.class);

    private final PlannerStateRepository repository;
    private final ExecutableSchedulingService scheduling;

    public MovedCommitmentRescuer(PlannerStateRepository repository,
                                  ExecutableSchedulingService scheduling) {
        this.repository = repository;
        this.scheduling = scheduling;
    }

    /**
     * Moves forward the day's commitments that the run has already left behind.
     *
     * @param userId     the owning user; never null
     * @param targetDay  the day being planned; never null
     * @param zone       the user's timezone; never null
     * @param lowerBound the run's earliest placeable instant — what "already gone by" means; never null
     * @param upperBound the bedtime edge; never null
     * @param walls      the day's hard walls, including the blocks just materialized; never null
     * @return the ids actually re-timed; never null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> rescue(UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime lowerBound,
                             OffsetDateTime upperBound, List<OccupiedInterval> walls) {
        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        List<OccupiedInterval> occupancy = new ArrayList<>(walls);
        List<UUID> moved = new ArrayList<>();
        for (MovableCommitment commitment
            : repository.loadMovableCommitments(userId, dayStart, dayEnd)) {
            if (!commitment.end().isBefore(lowerBound)) {
                // Still ahead: its hour is the user's and nothing has run it over.
                continue;
            }
            Optional<OffsetDateTime> slot = earliestFreeStart(
                commitment, occupancy, lowerBound, earlier(upperBound, dayEnd));
            if (slot.isEmpty()) {
                log.info("Commitment {} was overtaken and its day has no room left; it stays where it "
                    + "is — moving it to another day is the user's call", commitment.executableId());
                continue;
            }
            OffsetDateTime start = slot.get();
            OffsetDateTime end = start.plusMinutes(commitment.durationMinutes());
            if (scheduling.retimeWithinDay(commitment.executableId(), start, end, zone)) {
                moved.add(commitment.executableId());
                occupancy.add(new OccupiedInterval(commitment.executableId(), start, end, false));
            }
        }
        if (!moved.isEmpty()) {
            log.info("Re-timed {} commitment(s) an interruption had run over on {}", moved.size(),
                targetDay);
        }
        return moved;
    }

    /**
     * The earliest instant at or after {@code from} where the commitment fits without touching a wall,
     * scanning the walls in order. Empty when the rest of the day has no room for it.
     */
    private static Optional<OffsetDateTime> earliestFreeStart(MovableCommitment commitment,
                                                              List<OccupiedInterval> occupancy,
                                                              OffsetDateTime from, OffsetDateTime to) {
        List<OccupiedInterval> sorted = occupancy.stream()
            .sorted(Comparator.comparing(OccupiedInterval::start))
            .toList();
        OffsetDateTime cursor = from;
        while (!cursor.plusMinutes(commitment.durationMinutes()).isAfter(to)) {
            OffsetDateTime candidateEnd = cursor.plusMinutes(commitment.durationMinutes());
            OffsetDateTime finalCursor = cursor;
            Optional<OccupiedInterval> clash = sorted.stream()
                .filter(wall -> wall.overlaps(finalCursor, candidateEnd))
                .findFirst();
            if (clash.isEmpty()) {
                return Optional.of(cursor);
            }
            cursor = clash.get().end();
        }
        return Optional.empty();
    }

    private static OffsetDateTime earlier(OffsetDateTime left, OffsetDateTime right) {
        return left.isBefore(right) ? left : right;
    }
}
