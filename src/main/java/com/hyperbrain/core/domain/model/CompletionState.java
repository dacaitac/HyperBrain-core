package com.hyperbrain.core.domain.model;

/*
 * Design pattern: Value Object (enum-backed)
 * Reason: ADR-039 splits "completed" into two orthogonal predicates the whole system must
 * agree on; centralizing them in one domain VO keeps every SQL filter, rule and mirror
 * projection reading the same definition instead of re-deriving DONE/FAILED ad hoc.
 */

/**
 * The two completion predicates of an executable status (ADR-039):
 * <ul>
 *   <li>{@link #isClosed()} — the executable left the inventory ({@code DONE} <b>or</b>
 *       {@code FAILED}): it stops being schedulable, triggers DR-04 recurrence cloning and is
 *       mirrored as "checked" to satellites whose completion is a single flag.</li>
 *   <li>{@link #isAchieved()} — the outcome was a success ({@code DONE} only): the predicate
 *       streaks, {@code wig_hit} and the completion ledger consume. A {@code FAILED} closure is
 *       never an achievement — it resets streaks and stays out of every success aggregate.</li>
 * </ul>
 */
public enum CompletionState {
    /** Still in the inventory ({@code TODO}, {@code IN_PROGRESS}, {@code PLANNED}, {@code WAITING}). */
    OPEN,
    /** Closed successfully ({@code DONE}). */
    ACHIEVED,
    /** Closed as a sanctioned miss ({@code FAILED}). */
    FAILED;

    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";

    /**
     * Classifies a raw {@code core_executable.status} value.
     *
     * @param status the status string; null classifies as {@link #OPEN}
     * @return the completion state
     */
    public static CompletionState from(String status) {
        if (STATUS_DONE.equals(status)) {
            return ACHIEVED;
        }
        if (STATUS_FAILED.equals(status)) {
            return FAILED;
        }
        return OPEN;
    }

    /** @return true when the status left the inventory ({@code DONE} or {@code FAILED}) */
    public static boolean isClosed(String status) {
        return from(status) != OPEN;
    }

    /** @return true when the status is a success closure ({@code DONE}) */
    public static boolean isAchieved(String status) {
        return from(status) == ACHIEVED;
    }

    /** @return true when this state left the inventory */
    public boolean isClosed() {
        return this != OPEN;
    }

    /** @return true when this state is a success closure */
    public boolean isAchieved() {
        return this == ACHIEVED;
    }
}
