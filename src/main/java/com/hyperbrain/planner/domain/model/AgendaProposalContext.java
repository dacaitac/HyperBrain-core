package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The LLM-facing read model of one day's planning state (#61, HU-01c H3) — the input the cognitive
 * proposer turns into a prompt. It is the realization of the {@code UserStateReadModel} the planner
 * out-port refers to: a read-only projection assembled by {@code planner.application} from the day the
 * deterministic floor already resolved, plus the executable titles (the only extra read), so the
 * cognitive tier never reaches into planner persistence.
 *
 * <p><b>What the LLM may rearrange vs. what is a fixed wall</b> (ADR-009, authority model 2026-07-21):
 * <ul>
 *   <li>{@code candidateBlocks} — the run's block set the floor produced (the "arrangement" the LLM
 *       owns: reorder, retime, humanize, or DROP a non-WIG block). Every block's {@code executableId}
 *       is the block's stable id for this run — the closed set the proposal's {@code blockId} must draw
 *       from (anti-hallucination). ACTIVITY executables live here as <b>movable</b> candidates, never as
 *       walls;</li>
 *   <li>{@code agendaWalls} — read-only AGENDA windows only: fixed occupied space the LLM plans around
 *       and can never overlap;</li>
 *   <li>{@code wigExecutableIds} — the WIG blocks (F1 lead measures): a hard floor the LLM can never
 *       DROP nor expel;</li>
 *   <li>{@code frontierStart}/{@code frontierEnd} — the sleep frontier {@code [wake, bedtime]}; no
 *       block may fall outside it.</li>
 * </ul>
 *
 * <p>{@code highLoadQuota} and {@code energyCriterion} are soft guidance surfaced to the model (the F6
 * quota is <em>not</em> a hard wall the guard re-imposes — energy shaping is the LLM's authority).
 * {@code titles} is <b>untrusted content</b> (iOS/Notion display names): the prompt fences it in a
 * delimited section so it can never be read as instructions (anti prompt-injection).
 *
 * @param candidateBlocks  the run's block set (floor output); never null, one block per executable id
 * @param frontierStart    the sleep-frontier lower edge (wake); never null
 * @param frontierEnd      the sleep-frontier upper edge (bedtime); never null, after {@code frontierStart}
 * @param agendaWalls      the read-only AGENDA windows (fixed walls); never null
 * @param wigExecutableIds the WIG executable ids (never droppable); never null
 * @param highLoadQuota    the F6 high-load quota, soft guidance for the prompt; never negative
 * @param energyCriterion  the readable energy criterion; never blank
 * @param titles           executable id → display name (untrusted, delimited in the prompt); never null
 */
public record AgendaProposalContext(
    List<AgendaBlock> candidateBlocks,
    OffsetDateTime frontierStart,
    OffsetDateTime frontierEnd,
    List<OccupiedInterval> agendaWalls,
    Set<UUID> wigExecutableIds,
    int highLoadQuota,
    String energyCriterion,
    Map<UUID, String> titles
) {

    public AgendaProposalContext {
        if (frontierStart == null || frontierEnd == null) {
            throw new IllegalArgumentException("frontier edges must not be null");
        }
        if (!frontierEnd.isAfter(frontierStart)) {
            throw new IllegalArgumentException(
                "frontierEnd must be after frontierStart: " + frontierStart + " .. " + frontierEnd);
        }
        if (highLoadQuota < 0) {
            throw new IllegalArgumentException("highLoadQuota must be non-negative: " + highLoadQuota);
        }
        if (energyCriterion == null || energyCriterion.isBlank()) {
            throw new IllegalArgumentException("energyCriterion must not be blank");
        }
        candidateBlocks = candidateBlocks == null ? List.of() : List.copyOf(candidateBlocks);
        agendaWalls = agendaWalls == null ? List.of() : List.copyOf(agendaWalls);
        wigExecutableIds = wigExecutableIds == null ? Set.of() : Set.copyOf(wigExecutableIds);
        titles = titles == null ? Map.of() : Map.copyOf(titles);
    }

    /** @return the closed set of block ids for this run (the {@code blockId} enum the LLM must draw from) */
    public Set<UUID> runBlockIds() {
        return candidateBlocks.stream()
            .map(AgendaBlock::executableId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Looks up a candidate block by its run id.
     *
     * @param blockId the run block id
     * @return the matching candidate block, or null when the id is not in the run
     */
    public AgendaBlock candidate(UUID blockId) {
        return candidateBlocks.stream()
            .filter(b -> b.executableId().equals(blockId))
            .findFirst()
            .orElse(null);
    }
}
