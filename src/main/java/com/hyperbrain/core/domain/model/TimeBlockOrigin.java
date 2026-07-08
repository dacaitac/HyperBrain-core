package com.hyperbrain.core.domain.model;

/**
 * Who opened a {@code core_time_block} (ADR-013 D1).
 */
public enum TimeBlockOrigin {
    /** Created by the Planner engine during agenda generation (HU-01). */
    PLANNER,
    /** Auto-opened when a task became the single focus (DR-05) so imputation has a window. */
    FOCUS,
    /** Manually created by the user (future). */
    USER
}
