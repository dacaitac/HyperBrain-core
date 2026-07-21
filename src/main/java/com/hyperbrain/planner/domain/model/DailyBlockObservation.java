package com.hyperbrain.planner.domain.model;

/**
 * One planner block of a local day as seen at rollup time (H0 telemetry, #17): whether it reserved
 * the WIG (F1) and how many minutes of it were actually executed. The execution signal is the
 * settled {@code core_time_block.actual_duration_minutes} — the concrete, in-production record of a
 * block that reached {@code ACTIVE} and was frozen by a focus switch (SETTLED) or by the expiry
 * sweep (EXPIRED). It is null when the block was never executed (still PLANNED, or EXPIRED without
 * ever going ACTIVE), which honestly reads as "nothing observed".
 *
 * @param wig                    true when the block reserves the WIG's lead measure (F1)
 * @param actualDurationMinutes  settled executed minutes; null when the block was never executed
 */
public record DailyBlockObservation(boolean wig, Integer actualDurationMinutes) {

    /**
     * Whether this block counts as executed under a minimum-minutes temporal tolerance: it was
     * settled with an actual duration reaching {@code toleranceMinutes}. A block that barely started
     * (below the tolerance) or never ran (null) does not count.
     *
     * @param toleranceMinutes the minimum settled minutes for a block to count as executed
     * @return true when the block was executed for at least the tolerance
     */
    public boolean executed(int toleranceMinutes) {
        return actualDurationMinutes != null && actualDurationMinutes >= toleranceMinutes;
    }
}
