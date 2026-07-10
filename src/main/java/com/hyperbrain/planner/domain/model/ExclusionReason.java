package com.hyperbrain.planner.domain.model;

/**
 * Why an executable was left off the day's agenda. The floor never discards silently (Triángulo de
 * Control): every excluded executable carries one of these so the delivered agenda can explain the
 * cut.
 */
public enum ExclusionReason {
    /** The planning window filled up before this executable's turn came. */
    NO_ROOM_IN_WINDOW,
    /** Trimmed to keep the day within the F6 high-load quota (never applies to the WIG). */
    HIGH_LOAD_QUOTA_EXCEEDED,
    /** Its remaining effort was zero or unknown, so there was nothing to schedule. */
    NO_REMAINING_EFFORT,
    /** A read-only AGENDA executable — a wall, never schedulable (ADR-009). */
    READ_ONLY_AGENDA,
    /**
     * An active MCI with no lead measure: a WIG without a lead measure violates 4DX D2, so it is left
     * out of the reservation and flagged (never a silent default). Keyed by the MCI cycle id.
     */
    WIG_WITHOUT_LEAD_MEASURE,
    /**
     * A WIG dropped from the day's reservation because the degraded block budget was smaller than the
     * active portfolio and the required-pace ordering placed it below the cut. Keyed by the MCI cycle id.
     */
    WIG_BUDGET_EXCEEDED
}
