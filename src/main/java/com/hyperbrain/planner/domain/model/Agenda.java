package com.hyperbrain.planner.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The deterministic floor's output for one day (#6a): the ordered {@code PLANNED} blocks plus the
 * full account of what did <em>not</em> get scheduled and why. The floor never discards in silence
 * (Triángulo de Control), so the agenda always carries:
 *
 * <ul>
 *   <li>{@code blocks} — the placed blocks in chronological order, each with a readable reason;</li>
 *   <li>{@code excluded} — every executable left off the day paired with its {@link ExclusionReason};</li>
 *   <li>{@code paused} — the {@code IN_PROGRESS} executables that ended the run with no open block
 *       (surfaced explicitly so nothing "in progress" silently disappears);</li>
 *   <li>{@code energyCriterion} — the {@code Sleep Score → margin → quota} chain that drove the
 *       load trim (F3/F6), obligatory legibility;</li>
 *   <li>{@code degraded} — true when the floor fell back to F5 (WIG + a few urgents) on missing data
 *       or partial failure.</li>
 * </ul>
 *
 * <p><b>Daily uniqueness invariant (ADR-027 D5).</b> An executable belongs to <b>at most one block on a
 * day</b>: no executable id may appear in more than one block's membership (anchor or companion) across
 * {@code blocks}. This is the in-memory half of the invariant that a unique index on
 * {@code core_time_block_member} enforces at the storage layer; the aggregate rejects a violating day
 * at construction so a grouping bug can never smuggle the same executable into two themes the same day
 * (which would double-count its effort and split its {@code wigHit} signal). The generator already
 * upholds it by skipping an already-{@code placed} executable; this guard makes it a hard aggregate
 * invariant regardless of who composed the blocks.
 *
 * @param blocks          the placed blocks, chronological; never null
 * @param excluded        the excluded executables with reasons; never null
 * @param paused          the IN_PROGRESS executables with no open block; never null
 * @param energyCriterion the readable load-trim criterion; never blank
 * @param degraded        whether F5 degraded mode produced this agenda
 */
public record Agenda(
    List<AgendaBlock> blocks,
    List<ExcludedExecutable> excluded,
    List<UUID> paused,
    String energyCriterion,
    boolean degraded
) {

    public Agenda {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
        paused = paused == null ? List.of() : List.copyOf(paused);
        if (energyCriterion == null || energyCriterion.isBlank()) {
            throw new IllegalArgumentException("energyCriterion must not be blank");
        }
        Set<UUID> seen = new HashSet<>();
        for (AgendaBlock block : blocks) {
            for (UUID member : block.members()) {
                if (!seen.add(member)) {
                    throw new IllegalArgumentException(
                        "an executable may belong to at most one block per day (ADR-027 D5): " + member);
                }
            }
        }
    }
}
