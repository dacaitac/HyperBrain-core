package com.hyperbrain.core.domain.model;

/**
 * Aggregate of the user-defined subtasks of one parent executable (DR-07, ADR-013 D4).
 * System-generated focus snapshots are excluded from both counters.
 *
 * @param total user subtasks under the parent
 * @param done  user subtasks with status {@code DONE}
 */
public record SubtaskCounts(int total, int done) {

    /**
     * Materialized progress of the parent: {@code done / total}, or null when the parent has
     * no user subtasks — null means "no progress unit", never 0 % (a task without subtasks is
     * not stalled).
     *
     * @return progress in [0, 1], or null when {@code total == 0}
     */
    public Double progress() {
        return total == 0 ? null : (double) done / total;
    }
}
