package com.hyperbrain.planner.domain.model;

/**
 * Why an executable was left off the day's agenda. The floor never discards silently (Triángulo de
 * Control): every excluded executable carries one of these so the delivered agenda can explain the
 * cut.
 */
public enum ExclusionReason {
    /** The planning window filled up before this executable's turn came. */
    NO_ROOM_IN_WINDOW,
    /** Its remaining effort was zero or unknown, so there was nothing to schedule. */
    NO_REMAINING_EFFORT,
    /** A read-only AGENDA executable — a wall, never schedulable (ADR-009). */
    READ_ONLY_AGENDA,
    /**
     * A calendar-event type ({@code ACTIVITY}, {@code LEARNING_SESSION}) that already <b>is</b> a block
     * of time: it carries its own window, so it is never put inside one. The planner may still move it
     * in hour within its day — never to another day, which the user owns (ADR-040 D9).
     */
    NOT_CONTAINABLE,
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
