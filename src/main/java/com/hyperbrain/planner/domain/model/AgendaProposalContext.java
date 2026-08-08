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
 * deterministic floor already resolved, plus the display names of everything it refers to, so the
 * cognitive tier never reaches into planner persistence.
 *
 * <p><b>What the LLM may rearrange vs. what is a fixed wall</b> (ADR-009, authority model 2026-07-21):
 * <ul>
 *   <li>{@code candidateBlocks} — the run's block set the floor produced (the "arrangement" the LLM
 *       owns: reorder, retime, humanize, or DROP a non-WIG block). Every block's {@code executableId}
 *       is the block's stable id for this run — the closed set the proposal's {@code blockId} must draw
 *       from (anti-hallucination). An {@code ACTIVITY} never appears here: it owns a calendar window of
 *       its own, so it is not something the day is arranged <em>around</em> — it arrives among the walls
 *       below;</li>
 *   <li>{@code agendaWalls} — read-only AGENDA windows only: fixed occupied space the LLM plans around
 *       and can never overlap;</li>
 *   <li>{@code occupiedWalls} — the day's <b>real</b> occupancy: every window somebody already owns —
 *       the blocks the user created himself, the ones already closed, the ones this run may no longer
 *       re-time because they have started, and the activities and study sessions standing on the day.
 *       Just as fixed as an AGENDA window and for the same reason: that time is spoken for by someone
 *       other than this run. They were once absent from this read model, and the consequence was
 *       exactly what one would expect — the model could not see the user's own block, so it planned
 *       straight over it;</li>
 *   <li>{@code wigExecutableIds} — the WIG blocks (F1 lead measures): a hard floor the LLM can never
 *       DROP nor expel;</li>
 *   <li>{@code frontierStart}/{@code frontierEnd} — the sleep frontier {@code [wake, bedtime]}; no
 *       block may fall outside it;</li>
 *   <li>{@code bands} — the stretch of the day each block may be <b>retimed within</b>
 *       ({@link RetimingBand}): the band of the day it was born in, widened to a meal's plausible hours
 *       when the band holds one. Retiming is the model's authority; leaving the band is not, because
 *       the band is the shape of the day and a block that leaves it dissolves that shape — the
 *       production case being «Casa» at seven in the morning. A block with no entry here comes from no
 *       band and is unconfined.</li>
 * </ul>
 *
 * <p>{@code highLoadQuota}, {@code energyCriterion} and {@code sleepSessions} are soft guidance
 * surfaced to the model (the F6 quota is <em>not</em> a hard wall the guard re-imposes — energy
 * shaping is the LLM's authority). {@code sleepSessions} is when the user actually slept in the last
 * day — the night and any naps — so the model can order the day around it: a nap that ended at 13:56
 * is the reason not to put deep work at 14:00 as if the morning had never been slept through.
 * {@code titles} is <b>untrusted content</b> (iOS/Notion display names): the prompt fences it in a
 * delimited section so it can never be read as instructions (anti prompt-injection).
 *
 * <p><b>The walls travel named and with their contents</b> ({@code titles} covers them too, and
 * {@code wallMembers} says what each one holds). A wall reduced to bare geometry only says «this hour
 * is taken», and that is not what the day the user pre-organised means: his blocks and the commitments
 * standing on the day are the signal of what today actually <em>is</em> — whether there is a stand-up
 * at eight and a review at half past two, or whether it is a Saturday with none of them. The template
 * is a generic reference; this is the day. It is context for the model's judgement, exactly like the
 * sleep windows: nothing extra is re-imposed by the guard, which keeps re-imposing the geometry alone.
 *
 * @param candidateBlocks  the run's block set (floor output); never null, one block per executable id
 * @param frontierStart    the sleep-frontier lower edge (wake); never null
 * @param frontierEnd      the sleep-frontier upper edge (bedtime); never null, after {@code frontierStart}
 * @param agendaWalls      the read-only AGENDA windows (fixed walls); never null
 * @param occupiedWalls    the day's real occupied blocks (fixed walls); never null
 * @param wigExecutableIds the WIG executable ids (never droppable); never null
 * @param highLoadQuota    the F6 high-load quota, soft guidance for the prompt; never negative
 * @param energyCriterion  the readable energy criterion; never blank
 * @param titles           executable id → display name (untrusted, delimited in the prompt); never null
 * @param bands            block id → the band it may be retimed within; never null, may be partial
 * @param sleepSessions    when the user slept over the last day (night + naps), chronological; never
 *                         null, empty when nothing was recorded
 * @param wallMembers      wall id → what that window already holds, in container order; never null,
 *                         partial (a commitment holds nothing, and neither does an empty block)
 */
public record AgendaProposalContext(
    List<AgendaBlock> candidateBlocks,
    OffsetDateTime frontierStart,
    OffsetDateTime frontierEnd,
    List<OccupiedInterval> agendaWalls,
    List<OccupiedInterval> occupiedWalls,
    Set<UUID> wigExecutableIds,
    int highLoadQuota,
    String energyCriterion,
    Map<UUID, String> titles,
    Map<UUID, RetimingBand> bands,
    List<SleepSession> sleepSessions,
    Map<UUID, List<UUID>> wallMembers
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
        occupiedWalls = occupiedWalls == null ? List.of() : List.copyOf(occupiedWalls);
        wigExecutableIds = wigExecutableIds == null ? Set.of() : Set.copyOf(wigExecutableIds);
        titles = titles == null ? Map.of() : Map.copyOf(titles);
        bands = bands == null ? Map.of() : Map.copyOf(bands);
        sleepSessions = sleepSessions == null ? List.of() : List.copyOf(sleepSessions);
        wallMembers = wallMembers == null ? Map.of() : Map.copyOf(wallMembers);
    }

    /**
     * What an already-occupied window holds.
     *
     * @param wallId the wall's executable id
     * @return its members in container order, or an empty list when it holds nothing
     */
    public List<UUID> membersOf(UUID wallId) {
        return wallMembers.getOrDefault(wallId, List.of());
    }

    /**
     * The band a block may be retimed within.
     *
     * @param blockId the run block id
     * @return the block's band, or null when it comes from no band of the day (unconfined)
     */
    public RetimingBand band(UUID blockId) {
        return bands.get(blockId);
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
