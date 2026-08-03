package com.hyperbrain.prioritizer.domain.model;

/**
 * The kind of a {@code core_cycle} (ADR-015: the cycle type absorbs the former CORE_PROJECT and every
 * horizon level). The GTD horizon ladder — {@link #MCI} (the 4DX WIG), {@link #GOAL}, {@link #OBJECTIVE},
 * {@link #PROJECT}, {@link #PHASE} — runs from the most crucial commitment down to structural
 * subdivision. {@link #ROUTINE} (maintenance) and {@link #AREA} (life-area classification) are
 * <b>orthogonal</b> to that ladder, not steps on it.
 *
 * <p>Each type carries an alignment band weight {@code W(type)} used by the graded alignment factor of
 * the Priority Score (see {@link AlignmentWeights}); the weights are calibrable domain constants, not
 * part of the enum.
 *
 * <p><b>{@link #AREA} — classification, out of the alignment chain (ADR-036).</b> An {@code AREA} row
 * (e.g. "Family", "Money") groups commitments by life area through the {@code core_cycle_area} M:N
 * bridge, never through {@code parent_cycle_id}. It is therefore <em>never</em> an ancestor in the
 * {@code parent_cycle_id} walk of the alignment resolver or the urgency SQL, and its band weight is
 * {@code 0.0}: it contributes no priority signal. An {@code AREA} is perpetual (no {@code end_date},
 * always {@code ACTIVE}).
 */
public enum CycleType {
    MCI,
    GOAL,
    OBJECTIVE,
    PROJECT,
    PHASE,
    ROUTINE,
    AREA
}
