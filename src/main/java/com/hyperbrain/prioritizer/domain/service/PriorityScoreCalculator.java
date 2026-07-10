package com.hyperbrain.prioritizer.domain.service;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.prioritizer.domain.model.PriorityWeights;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure domain service that computes the normalized Priority Score (v2) and ranks the day's
 * executables (engine doc: {@code 02-architecture/engines/prioritizer.md}; formula fixed by Daniel —
 * do not re-derive here).
 *
 * <p><b>The v2 fix: normalize every factor to {@code [0, 1]} before weighting.</b> v1 weighted raw,
 * heterogeneous scales (Fibonacci 1–8, 0–6, 0–5, 0–1), so the declared 20% alignment weight carried
 * only ~4% of real influence. Each factor is mapped onto {@code [0, 1]} on its documented fixed
 * scale (min–max against a known range, not batch-relative), then weighted:
 *
 * <pre>{@code
 *   impactN    = (impact − 1) / 7        // Fibonacci 1–8
 *   urgencyN   = min(urgency, 6) / 6     // 0–6 (overdue caps at 6)
 *   effortInv  = (5 − effort) / 5        // 0–5, inverted so Quick Wins score higher
 *   alignment  ∈ [0, 1]                  // already normalized (resolved upstream)
 *   P = impactN·0.4 + urgencyN·0.3 + effortInv·0.1 + alignment·0.2
 * }</pre>
 *
 * <p>Missing profile factors are treated as their neutral floor ({@code 0.0}) so a partially
 * profiled executable still scores honestly from the factors it has, rather than being dropped.
 *
 * <p>Design pattern: single-algorithm domain service (Strategy deliberately avoided, YAGNI) — the
 * formula is one fixed domain invariant, kept in one place. Alternate weights arrive as data via
 * {@link PriorityWeights}, not as a new algorithm.
 */
public class PriorityScoreCalculator {

    private static final double IMPACT_MIN = 1.0;
    private static final double IMPACT_RANGE = 7.0;
    private static final double URGENCY_CAP = 6.0;
    private static final double EFFORT_MAX = 5.0;

    private final PriorityWeights weights;
    private final AlignmentResolver alignmentResolver;

    /**
     * Creates a calculator using the sanctioned default weights ({@link PriorityWeights#DEFAULT}) and
     * the default alignment resolver ({@link AlignmentResolver#AlignmentResolver()}).
     */
    public PriorityScoreCalculator() {
        this(PriorityWeights.DEFAULT, new AlignmentResolver());
    }

    /**
     * Creates a calculator with explicit weights and alignment resolver (calibration seam).
     *
     * @param weights           the factor weights; never null
     * @param alignmentResolver the graded alignment policy; never null
     */
    public PriorityScoreCalculator(PriorityWeights weights, AlignmentResolver alignmentResolver) {
        if (weights == null) {
            throw new IllegalArgumentException("weights must not be null");
        }
        if (alignmentResolver == null) {
            throw new IllegalArgumentException("alignmentResolver must not be null");
        }
        this.weights = weights;
        this.alignmentResolver = alignmentResolver;
    }

    /**
     * Computes the Priority Score of one executable from its raw factors and resolved alignment.
     *
     * @param factors   the executable's un-normalized factors; never null
     * @param alignment the alignment factor already in {@code [0, 1]} (see {@link AlignmentResolver})
     * @return the score and its inverted-effort tie-breaker, {@code score ∈ [0, 1]}
     */
    public PriorityScore score(ExecutableFactors factors, double alignment) {
        double impactN = normalizeImpact(factors.impact());
        double urgencyN = normalizeUrgency(factors.urgencyRaw());
        double effortInv = invertEffort(factors.effort());

        double p = impactN * weights.wImpact()
            + urgencyN * weights.wUrgency()
            + effortInv * weights.wEffort()
            + alignment * weights.wAlignment();

        return new PriorityScore(factors.executableId(), p, effortInv);
    }

    /**
     * Scores every executable of the day and returns them ranked highest-first. Ties on the score
     * are broken by the inverted-effort factor (Quick Wins first), and any residual tie by executable
     * id so the ordering is deterministic.
     *
     * @param factorsList      the day's executables' raw factors; never null (may be empty)
     * @param alignmentContexts the alignment context per cycle for this run, keyed by cycle id; never
     *                          null, empty when no cycle has an active aligning ancestor
     * @return the scored executables ranked by priority, highest first; never null
     */
    public List<PriorityScore> rank(
        List<ExecutableFactors> factorsList, Map<UUID, CycleAlignmentContext> alignmentContexts) {
        return factorsList.stream()
            .map(factors -> score(factors, alignmentResolver.resolve(factors.cycleId(), alignmentContexts)))
            .sorted(Comparator
                .comparingDouble(PriorityScore::score).reversed()
                .thenComparing(Comparator.comparingDouble(PriorityScore::effortInv).reversed())
                .thenComparing(PriorityScore::executableId))
            .toList();
    }

    private static double normalizeImpact(Integer impact) {
        if (impact == null) {
            return 0.0;
        }
        return (impact - IMPACT_MIN) / IMPACT_RANGE;
    }

    private static double normalizeUrgency(double urgencyRaw) {
        double capped = Math.min(urgencyRaw, URGENCY_CAP);
        return capped / URGENCY_CAP;
    }

    private static double invertEffort(Double effort) {
        if (effort == null) {
            return 0.0;
        }
        return (EFFORT_MAX - effort) / EFFORT_MAX;
    }
}
