package com.hyperbrain.planner.domain.model;

import java.util.List;
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
    }
}
