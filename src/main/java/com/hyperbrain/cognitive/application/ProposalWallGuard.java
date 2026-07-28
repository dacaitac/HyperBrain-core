package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.application.AgendaPropuesta.BlockDecision;
import com.hyperbrain.cognitive.application.AgendaPropuesta.Placement;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The bounded hard-wall guard of the LLM proposal (HU-01c H3, authority model 2026-07-21). Unlike the
 * deterministic floor's {@code AgendaValidator}, which strips individual offending blocks, this guard
 * enforces the LLM's authority model: the model owns the <b>arrangement</b> (the when, the reordering,
 * humanization, dropping non-WIG blocks, moving ACTIVITY), and the guard re-imposes only the four
 * inviolable walls. It is <b>all-or-nothing</b>: if the proposal clears every wall it is accepted whole;
 * if it breaches any, the whole proposal is rejected and the caller degrades to the humanized floor —
 * the guard never half-corrects.
 *
 * <p><b>Walls re-imposed</b> (a breach of any → DEGRADED):
 * <ol>
 *   <li>{@code STRUCTURAL_IDENTITY} — the decisions cover the run's block-id set exactly: no invented id
 *       (anti-hallucination) and no silently dropped block (every id present once);</li>
 *   <li>{@code WIG_PROTECTED} — no WIG block is DROPped (F1 is a hard floor);</li>
 *   <li>{@code SLEEP_FRONTIER} — every surviving block stays inside {@code [wake, bedtime]} (ADR-013 D2);</li>
 *   <li>{@code AGENDA_READ_ONLY} — no surviving block overlaps a read-only AGENDA window (ADR-009).</li>
 * </ol>
 *
 * <p>The F6 high-load quota, transition buffers and block-vs-block spacing are deliberately <b>not</b>
 * walls here — they are the LLM's arrangement authority. All breaches are collected (not just the first)
 * so the pre-validation telemetry can report the full intervention picture.
 */
@Component
public class ProposalWallGuard {

    /**
     * Re-imposes the four bounded walls on a proposal.
     *
     * @param propuesta the parsed LLM proposal; never null
     * @param context   the run's read model (candidates, frontier, AGENDA walls, WIG ids); never null
     * @return the breaches found; empty when the proposal is accepted whole
     */
    public WallGuardResult check(AgendaPropuesta propuesta, AgendaProposalContext context) {
        List<WallBreach> breaches = new ArrayList<>();
        breaches.addAll(structuralBreaches(propuesta, context));
        for (BlockDecision decision : propuesta.decisions()) {
            breaches.addAll(blockBreaches(decision, context));
        }
        breaches.addAll(overlapBreaches(propuesta, context));
        return new WallGuardResult(List.copyOf(breaches));
    }

    /**
     * Identity diff: the decision id set must equal the run's block id set exactly — reported as one
     * STRUCTURAL_IDENTITY breach per offending id (invented, missing, or duplicated).
     */
    private static List<WallBreach> structuralBreaches(AgendaPropuesta propuesta,
                                                       AgendaProposalContext context) {
        List<WallBreach> breaches = new ArrayList<>();
        Set<UUID> runIds = context.runBlockIds();
        Set<UUID> seen = new java.util.HashSet<>();
        for (BlockDecision decision : propuesta.decisions()) {
            UUID id = decision.blockId();
            if (!runIds.contains(id)) {
                breaches.add(new WallBreach(id, ProposalWall.STRUCTURAL_IDENTITY)); // invented / hallucinated
            } else if (!seen.add(id)) {
                breaches.add(new WallBreach(id, ProposalWall.STRUCTURAL_IDENTITY)); // duplicated
            }
        }
        for (UUID runId : runIds) {
            if (!seen.contains(runId)) {
                breaches.add(new WallBreach(runId, ProposalWall.STRUCTURAL_IDENTITY)); // silently dropped
            }
        }
        return breaches;
    }

    /** The frontier / AGENDA / WIG / meal walls for one decision. */
    private static List<WallBreach> blockBreaches(BlockDecision decision, AgendaProposalContext context) {
        UUID id = decision.blockId();
        if (decision.placement() == Placement.DROP) {
            if (context.wigExecutableIds().contains(id)) {
                return List.of(new WallBreach(id, ProposalWall.WIG_PROTECTED));
            }
            return List.of();
        }
        AgendaBlock candidate = context.candidate(id);
        if (candidate == null) {
            // An invented id already produced a STRUCTURAL breach; nothing more to check geometrically.
            return List.of();
        }
        OffsetDateTime start = effectiveStart(decision, candidate);
        OffsetDateTime end = effectiveEnd(decision, candidate);

        List<WallBreach> breaches = new ArrayList<>();
        if (start.isBefore(context.frontierStart()) || end.isAfter(context.frontierEnd())) {
            breaches.add(new WallBreach(id, ProposalWall.SLEEP_FRONTIER));
        }
        boolean overlapsAgenda = context.agendaWalls().stream().anyMatch(w -> w.overlaps(start, end));
        if (overlapsAgenda) {
            breaches.add(new WallBreach(id, ProposalWall.AGENDA_READ_ONLY));
        }
        // Meal windows are permeable ONLY to the block that matches them: a block sitting entirely inside
        // a meal window IS the meal (breakfast/lunch/dinner block occupying its natural slot), which is
        // allowed; any other block that merely bleeds work across the meal boundary is a breach (core#50,
        // Part B). Without this the LLM could quietly schedule work over a protected meal.
        boolean intrudesOnMeal = context.mealAttractors().stream()
            .anyMatch(meal -> meal.overlaps(start, end) && !contains(meal, start, end));
        if (intrudesOnMeal) {
            breaches.add(new WallBreach(id, ProposalWall.MEAL_WINDOW));
        }
        return breaches;
    }

    /**
     * Block-vs-block non-overlap across the surviving (non-DROP) proposal (core#50, Part B): the LLM owns
     * the arrangement but two blocks may never share the same minutes. Reports the later-starting block
     * of each overlapping pair, so the breach points at the block that would collide.
     */
    private static List<WallBreach> overlapBreaches(AgendaPropuesta propuesta,
                                                    AgendaProposalContext context) {
        List<Placed> placed = new ArrayList<>();
        for (BlockDecision decision : propuesta.decisions()) {
            if (decision.placement() == Placement.DROP) {
                continue;
            }
            AgendaBlock candidate = context.candidate(decision.blockId());
            if (candidate == null) {
                continue;
            }
            placed.add(new Placed(decision.blockId(),
                effectiveStart(decision, candidate), effectiveEnd(decision, candidate)));
        }
        placed.sort(java.util.Comparator.comparing(Placed::start));
        List<WallBreach> breaches = new ArrayList<>();
        for (int i = 1; i < placed.size(); i++) {
            Placed current = placed.get(i);
            for (int j = 0; j < i; j++) {
                Placed earlier = placed.get(j);
                if (current.start().isBefore(earlier.end()) && current.end().isAfter(earlier.start())) {
                    breaches.add(new WallBreach(current.id(), ProposalWall.BLOCK_OVERLAP));
                    break;
                }
            }
        }
        return breaches;
    }

    private static OffsetDateTime effectiveStart(BlockDecision decision, AgendaBlock candidate) {
        return decision.placement() == Placement.MOVE ? decision.start() : candidate.start();
    }

    private static OffsetDateTime effectiveEnd(BlockDecision decision, AgendaBlock candidate) {
        return decision.placement() == Placement.MOVE ? decision.end() : candidate.end();
    }

    /** Whether {@code [start, end]} sits entirely inside the meal window. */
    private static boolean contains(OccupiedInterval meal, OffsetDateTime start, OffsetDateTime end) {
        return !start.isBefore(meal.start()) && !end.isAfter(meal.end());
    }

    /** A surviving block's effective placement, for the block-vs-block overlap scan. */
    private record Placed(UUID id, OffsetDateTime start, OffsetDateTime end) {
    }

    /** The bounded set of hard walls the guard re-imposes on an LLM proposal. */
    public enum ProposalWall {
        /** A proposed block fell outside the sleep frontier {@code [wake, bedtime]} (ADR-013 D2). */
        SLEEP_FRONTIER,
        /** A proposed block overlapped a read-only AGENDA window (ADR-009). */
        AGENDA_READ_ONLY,
        /** The WIG block was dropped or expelled — F1 is a hard floor. */
        WIG_PROTECTED,
        /** An invented, duplicated, or silently dropped block id (identity diff, anti-hallucination). */
        STRUCTURAL_IDENTITY,
        /** A block bled work across a protected meal window it does not itself occupy (core#50). */
        MEAL_WINDOW,
        /** Two surviving blocks overlapped in time — the arrangement double-booked the day (core#50). */
        BLOCK_OVERLAP
    }

    /**
     * One wall a proposal breached, tagged with the offending block id.
     *
     * @param blockId the offending run block id; never null
     * @param wall    the wall it breached; never null
     */
    public record WallBreach(UUID blockId, ProposalWall wall) {
        public WallBreach {
            if (blockId == null || wall == null) {
                throw new IllegalArgumentException("blockId and wall must not be null");
            }
        }
    }

    /**
     * The outcome of a bounded wall check: every breach found. A clean result (no breaches) means the
     * LLM's arrangement is accepted whole; any breach degrades the whole proposal.
     *
     * @param breaches the breaches found, in evaluation order; never null
     */
    public record WallGuardResult(List<WallBreach> breaches) {
        public WallGuardResult {
            breaches = breaches == null ? List.of() : List.copyOf(breaches);
        }

        /** @return true when the proposal cleared every wall */
        public boolean clean() {
            return breaches.isEmpty();
        }
    }
}
