package com.hyperbrain.prioritizer.domain.model;

/**
 * The GTD horizon ladder a {@code core_cycle} can sit on (ADR-015: the cycle type absorbs the former
 * CORE_PROJECT and every horizon level). Ordered from the most crucial commitment ({@link #MCI}, the
 * 4DX WIG) down to plain maintenance ({@link #ROUTINE}).
 *
 * <p>Each level carries an alignment band weight {@code W(type)} used by the graded alignment factor
 * of the Priority Score (see {@link AlignmentWeights}). The weights are calibrable domain constants,
 * not part of the enum.
 */
public enum CycleType {
    MCI,
    GOAL,
    OBJECTIVE,
    PROJECT,
    PHASE,
    ROUTINE
}
