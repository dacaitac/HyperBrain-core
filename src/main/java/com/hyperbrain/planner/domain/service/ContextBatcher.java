package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.SchedulableExecutable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regroups the ranked executables by context so same-context work lands adjacently, cutting the
 * context-switching cost of a fragmented day (H1 rule 4, HU-01c). This is a <b>tie-break inside a
 * priority band, never a reordering across bands</b>: the Prioritizer's order is authoritative, so the
 * batcher only permutes executables whose scores are close enough to be considered comparable.
 *
 * <p><b>Banding.</b> Walking the already-ranked (score-descending) list, a band is a maximal contiguous
 * run whose leader's score minus the current candidate's score stays within {@code batchBandWidth}.
 * When a candidate falls outside the band, the band closes and a new one opens at the candidate. Bands
 * therefore keep their relative order — no lower-priority band ever jumps ahead of a higher one.
 *
 * <p><b>Within a band.</b> Executables are stably grouped by {@link SchedulableExecutable#contextKey()}
 * (cycle, else type): the first-seen order of contexts is preserved, and within a context the original
 * ranked order is preserved. The result is deterministic and idempotent.
 *
 * <p>Design pattern: single-algorithm domain service (a pure list transform) — no state, no clock.
 */
public class ContextBatcher {

    /**
     * Returns the ranked list regrouped by context within comparable-priority bands.
     *
     * @param ranked         the executables ordered highest priority first; never null
     * @param batchBandWidth the priority-score tolerance defining a band; {@code <= 0} disables
     *                       batching and returns the input order unchanged
     * @return a new list with the same elements, regrouped by context inside each band
     */
    public List<SchedulableExecutable> batch(List<SchedulableExecutable> ranked, double batchBandWidth) {
        if (ranked == null) {
            throw new IllegalArgumentException("ranked must not be null");
        }
        if (batchBandWidth <= 0.0 || ranked.size() < 2) {
            return List.copyOf(ranked);
        }

        List<SchedulableExecutable> result = new ArrayList<>(ranked.size());
        int index = 0;
        while (index < ranked.size()) {
            int bandEnd = bandEnd(ranked, index, batchBandWidth);
            result.addAll(groupByContext(ranked.subList(index, bandEnd)));
            index = bandEnd;
        }
        return result;
    }

    /**
     * The exclusive end index of the band opened at {@code start}: the first position whose score drops
     * more than {@code batchBandWidth} below the band leader's score.
     */
    private static int bandEnd(List<SchedulableExecutable> ranked, int start, double batchBandWidth) {
        double leaderScore = ranked.get(start).rankingScore();
        int end = start + 1;
        while (end < ranked.size() && leaderScore - ranked.get(end).rankingScore() <= batchBandWidth) {
            end++;
        }
        return end;
    }

    /**
     * Stably groups a band's executables by context key, preserving both the first-appearance order of
     * contexts and the ranked order within each context.
     */
    private static List<SchedulableExecutable> groupByContext(List<SchedulableExecutable> band) {
        Map<Object, List<SchedulableExecutable>> byContext = new LinkedHashMap<>();
        for (SchedulableExecutable executable : band) {
            byContext.computeIfAbsent(executable.contextKey(), key -> new ArrayList<>()).add(executable);
        }
        List<SchedulableExecutable> grouped = new ArrayList<>(band.size());
        byContext.values().forEach(grouped::addAll);
        return grouped;
    }
}
