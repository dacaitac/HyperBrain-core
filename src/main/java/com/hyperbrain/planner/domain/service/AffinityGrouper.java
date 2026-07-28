package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.SchedulableExecutable;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the ranked executables into affinity blocks the deterministic floor places as a single themed
 * container (ADR-027, core#50). It runs <em>after</em> {@link ContextBatcher} has already regrouped the
 * ranked list so same-context work sits adjacently: this grouper then merges those adjacent runs into
 * one block, cutting the day's fragmentation into a few coherent containers instead of many 1:1 blocks.
 *
 * <p><b>A group is a maximal contiguous run</b> of the (already context-batched) list whose members all
 * share:
 * <ul>
 *   <li><b>the same context</b> ({@link SchedulableExecutable#contextKey()} — cycle, else type);</li>
 *   <li><b>a comparable priority band</b> — the leader's score minus the candidate's stays within
 *       {@code batchBandWidth} (the same banding rule {@link ContextBatcher} uses, so a low-priority
 *       task never rides into a high-priority block);</li>
 *   <li><b>load compatibility</b> — at most one high-load member per group, so a themed container never
 *       stacks cognitive load (F6 spirit); a second high-load member opens a new group;</li>
 *   <li><b>the WIP cap</b> — no more than {@code maxMembers} executables per group, so a block stays a
 *       small, humane unit rather than an unbounded pile.</li>
 * </ul>
 * A candidate that breaks any of these closes the current group and opens a new one at the candidate.
 * The anchor of each group is its first (highest-ranked) member, and the ranked order is preserved
 * throughout, so the result is deterministic and idempotent.
 *
 * <p><b>WIG blocks never reach here.</b> They are reserved and marked placed by F1 before the rank fill
 * builds its units, so every executable this grouper sees is non-WIG (ADR-027 D4 keeps WIG blocks
 * atomic).
 *
 * <p>Design pattern: single-algorithm domain service (a pure list transform) — no state, no clock.
 */
public class AffinityGrouper {

    /**
     * Groups a context-batched, already-filtered ranked list into affinity runs.
     *
     * @param placeable      the executables to place, in ranked order, already context-batched and
     *                       pre-filtered (no WIG, no read-only AGENDA, positive effort); never null
     * @param highLoadDrainFloor the {@code energy_drain} at/above which a member is high-load
     * @param batchBandWidth the priority-score tolerance defining a band; {@code <= 0} keeps every
     *                       executable in its own singleton group (grouping disabled)
     * @param maxMembers     the WIP cap — the maximum number of members per group; must be positive
     * @return the groups, each a non-empty list of executables anchored on its first member; never null
     */
    public List<List<SchedulableExecutable>> group(List<SchedulableExecutable> placeable,
                                                   int highLoadDrainFloor, double batchBandWidth,
                                                   int maxMembers) {
        if (placeable == null) {
            throw new IllegalArgumentException("placeable must not be null");
        }
        if (maxMembers < 1) {
            throw new IllegalArgumentException("maxMembers must be positive: " + maxMembers);
        }

        List<List<SchedulableExecutable>> groups = new ArrayList<>();
        if (placeable.isEmpty()) {
            return groups;
        }
        // Grouping disabled (raw floor / no batching band): every executable is its own block.
        if (batchBandWidth <= 0.0 || maxMembers == 1) {
            for (SchedulableExecutable executable : placeable) {
                groups.add(List.of(executable));
            }
            return groups;
        }

        List<SchedulableExecutable> current = new ArrayList<>();
        double leaderScore = 0.0;
        int highLoadInGroup = 0;
        for (SchedulableExecutable executable : placeable) {
            boolean highLoad = executable.isHighLoad(highLoadDrainFloor);
            boolean fits = !current.isEmpty()
                && executable.contextKey().equals(current.get(0).contextKey())
                && leaderScore - executable.rankingScore() <= batchBandWidth
                && current.size() < maxMembers
                && highLoadInGroup + (highLoad ? 1 : 0) <= 1;
            if (!fits) {
                if (!current.isEmpty()) {
                    groups.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                leaderScore = executable.rankingScore();
                highLoadInGroup = 0;
            }
            current.add(executable);
            if (highLoad) {
                highLoadInGroup++;
            }
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return groups;
    }
}
