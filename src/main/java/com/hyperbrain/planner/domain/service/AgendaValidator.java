package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.ValidatedAgenda;
import com.hyperbrain.planner.domain.model.ValidationContext;
import com.hyperbrain.planner.domain.model.ValidationViolation;
import com.hyperbrain.planner.domain.model.ValidationViolation.Wall;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The non-negotiable guard of the agenda floor (#6a): a pure domain service that re-imposes every
 * hard wall on a proposed set of blocks before they are persisted as {@code PLANNED}, independently
 * of how they were produced. Built here, in the deterministic floor, precisely so the future LLM
 * tier (#6b) reuses the same guard — the LLM's freedom ends at this frontier.
 *
 * <p><b>Walls re-imposed</b> (planner engine doc, ADR-009/ADR-013 D2), in evaluation order per block:
 * <ol>
 *   <li>a read-only AGENDA executable is never schedulable as work (ADR-009);</li>
 *   <li>no block may fall outside the sleep frontier {@code [wake, bedtime]} (ADR-013 D2);</li>
 *   <li>no block may overlap a read-only AGENDA window (ADR-009);</li>
 *   <li>no block may overlap an occupied/SETTLED block or a previously accepted block;</li>
 *   <li>high-load blocks beyond the F6 quota are rejected — <b>except the WIG</b>, which F1 makes
 *       intocable (energy never trims it).</li>
 * </ol>
 *
 * <p>A rejected block is stripped from the accepted set and reported as a
 * {@link ValidationViolation}; it is never silently dropped nor persisted. Accepted blocks are
 * returned chronologically.
 *
 * <p>Design pattern: single-algorithm domain service — one fixed set of invariants, kept in one
 * place, reused across both agenda producers.
 */
public class AgendaValidator {

    /**
     * Validates a proposed agenda against the hard walls.
     *
     * @param proposed the blocks a generator (or the LLM) proposed; never null
     * @param context  the walls to re-impose (frontier, occupied, F6 quota, AGENDA ids); never null
     * @return the accepted blocks plus every rejection, each tagged with the wall it hit
     */
    public ValidatedAgenda validate(List<AgendaBlock> proposed, ValidationContext context) {
        if (proposed == null) {
            throw new IllegalArgumentException("proposed must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        List<AgendaBlock> ordered = proposed.stream()
            .sorted(Comparator.comparing(AgendaBlock::start))
            .toList();

        List<AgendaBlock> accepted = new ArrayList<>();
        List<ValidationViolation> violations = new ArrayList<>();
        List<OccupiedInterval> walls = new ArrayList<>(context.occupied());
        int highLoadUsed = 0;

        for (AgendaBlock block : ordered) {
            Wall wall = firstWall(block, context, walls, highLoadUsed);
            if (wall != null) {
                violations.add(new ValidationViolation(block.executableId(), wall));
                continue;
            }
            accepted.add(block);
            walls.add(new OccupiedInterval(block.executableId(), block.start(), block.end(), false));
            if (block.highLoad()) {
                highLoadUsed++;
            }
        }
        return new ValidatedAgenda(accepted, violations);
    }

    /**
     * The first wall this block violates, or null when it clears them all. The WIG is exempt from the
     * F6 quota only; it must still respect the frontier and never overlap.
     */
    private static Wall firstWall(AgendaBlock block, ValidationContext context,
                                  List<OccupiedInterval> walls, int highLoadUsed) {
        if (context.readOnlyAgendaIds().contains(block.executableId())) {
            return Wall.SCHEDULES_READ_ONLY_AGENDA;
        }
        if (outsideFrontier(block, context)) {
            return Wall.OUTSIDE_SLEEP_FRONTIER;
        }
        OccupiedInterval clash = overlappingWall(block, walls);
        if (clash != null) {
            return clash.readOnlyAgenda() ? Wall.OVERLAPS_READ_ONLY_AGENDA : Wall.OVERLAPS_OCCUPIED;
        }
        if (block.highLoad() && !block.wig() && highLoadUsed >= context.highLoadQuota()) {
            return Wall.HIGH_LOAD_QUOTA_EXCEEDED;
        }
        return null;
    }

    private static boolean outsideFrontier(AgendaBlock block, ValidationContext context) {
        return block.start().isBefore(context.frontierStart())
            || block.end().isAfter(context.frontierEnd());
    }

    private static OccupiedInterval overlappingWall(AgendaBlock block, List<OccupiedInterval> walls) {
        OffsetDateTime start = block.start();
        OffsetDateTime end = block.end();
        return walls.stream()
            .filter(wall -> wall.overlaps(start, end))
            .findFirst()
            .orElse(null);
    }
}
