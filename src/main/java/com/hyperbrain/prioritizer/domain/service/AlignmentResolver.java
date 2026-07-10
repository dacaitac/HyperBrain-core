package com.hyperbrain.prioritizer.domain.service;

import com.hyperbrain.prioritizer.domain.model.AlignmentWeights;
import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.CycleType;

import java.util.Map;
import java.util.UUID;

/**
 * Pure domain policy that turns an executable's owning cycle into the {@code [0, 1]} alignment factor
 * of the Priority Score: the <em>graded</em> alignment with the active MCI/horizon cycles of the
 * moment (Daniel, Comité 2026-07-09 — implement the formula, do not re-derive it).
 *
 * <p><b>Formula.</b> For an executable whose cycle is {@code k}:
 * <pre>{@code alignment(k) = max over every ACTIVE ancestor c of k (reachable up parent_cycle_id) of
 *                            W(c.type) · δ(d),   d = hops from k up to c }</pre>
 * with no active ancestor of a mapped type yielding {@code 0.0}, and an executable with no cycle
 * yielding {@code 0.0}. {@code W} (band weight per {@link CycleType}) and {@code δ} (distance decay)
 * are the calibrable constants of {@link AlignmentWeights}.
 *
 * <p><b>Coach cap (Daniel, Comité 2026-07-09).</b> A cycle whose <em>own</em> nature is maintenance
 * ({@code type = ROUTINE}) must not inherit a high value merely by hanging structurally off an MCI:
 * the final value of an executable whose own cycle is {@code ROUTINE} is capped at the ceiling of its
 * own band, {@code W(ROUTINE) · δ(0)}. This is applied as pushed, and reported for confirmation of the
 * exact semantics of the "maintenance that <em>is</em> the crucial commitment of the quarter" case.
 *
 * <p>The traversal (walking {@code core_cycle.parent_cycle_id}, guarding against the free-form graph's
 * possible cycles) is done once per run by the read port; this service only applies the grading policy
 * so the decision stays in the domain and testable.
 *
 * <p>Design pattern: this is a Strategy seam kept as a single class (YAGNI) — alternate calibrations
 * arrive as data via {@link AlignmentWeights}, not as a new algorithm.
 */
public class AlignmentResolver {

    private static final double UNALIGNED = 0.0;

    private final AlignmentWeights weights;

    /** Creates a resolver using the sanctioned default weights ({@link AlignmentWeights#DEFAULT}). */
    public AlignmentResolver() {
        this(AlignmentWeights.DEFAULT);
    }

    /**
     * Creates a resolver with explicit weights (calibration seam).
     *
     * @param weights the band/decay weights; never null
     */
    public AlignmentResolver(AlignmentWeights weights) {
        if (weights == null) {
            throw new IllegalArgumentException("weights must not be null");
        }
        this.weights = weights;
    }

    /**
     * Resolves the graded alignment factor for one executable.
     *
     * @param cycleId  the executable's owning cycle; may be null (unassigned → {@code 0.0})
     * @param contexts the alignment context per cycle for this run, keyed by cycle id; never null. A
     *                 cycle absent from the map (or with no active ancestor) resolves to {@code 0.0}
     * @return the graded alignment in {@code [0, 1]}
     */
    public double resolve(UUID cycleId, Map<UUID, CycleAlignmentContext> contexts) {
        if (cycleId == null) {
            return UNALIGNED;
        }
        CycleAlignmentContext context = contexts.get(cycleId);
        if (context == null) {
            return UNALIGNED;
        }

        double best = UNALIGNED;
        for (CycleAlignmentContext.AncestorLink link : context.activeAncestors()) {
            double candidate = weights.bandWeight(link.ancestorType()) * weights.decay(link.distance());
            if (candidate > best) {
                best = candidate;
            }
        }

        return applyCoachCap(context.ownType(), best);
    }

    /**
     * Caps a maintenance cycle's value at its own band ceiling. An executable whose own cycle is
     * {@code ROUTINE} cannot exceed {@code W(ROUTINE) · δ(0)} however crucial its ancestor is.
     *
     * @param ownType the executable's own cycle type
     * @param value   the ungapped {@code max} value
     * @return the value, capped when the own type is maintenance
     */
    private double applyCoachCap(CycleType ownType, double value) {
        if (ownType != CycleType.ROUTINE) {
            return value;
        }
        double ceiling = weights.bandWeight(CycleType.ROUTINE) * weights.decay(0);
        return Math.min(value, ceiling);
    }
}
