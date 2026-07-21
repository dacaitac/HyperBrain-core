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
    WIG_BUDGET_EXCEEDED,
    /**
     * A block dropped by the humanized floor (H1) because its duration fell below the minimum viable
     * block: a sliver is left out rather than fragmenting the day (never applies to the WIG).
     */
    BELOW_MIN_BLOCK,
    /**
     * A block trimmed by the humanized floor (H1) to keep the day within the sanctioned occupancy band:
     * the day is deliberately left with slack rather than packed to 100% (never applies to the WIG).
     */
    OVER_OCCUPANCY_CAP
}
