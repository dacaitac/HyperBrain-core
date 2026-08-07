package com.hyperbrain.core.domain.model;

/**
 * Who is taking an executable out of its block — and therefore what happens to the hour the block had
 * copied onto it (ADR-040 D10, an amendment to ADR-012 because only one of the two situations was
 * modelled).
 */
public enum ReleaseCause {

    /**
     * The user pulled it out. <b>The hour survives.</b> You took something out of a block; you did not
     * ask to lose the time you had for it.
     */
    USER_DETACH(false),

    /**
     * The planner withdrew the block. <b>The day survives, the hour dies</b>: the date falls back to the
     * midnight placeholder of its own day. The hour belonged to the block, not to the user, so keeping
     * it would leave the task pointing at a window that no longer exists.
     */
    PLANNER_WITHDRAWAL(true);

    private final boolean resetsHour;

    ReleaseCause(boolean resetsHour) {
        this.resetsHour = resetsHour;
    }

    /** @return true when the released member's time of day falls back to the midnight placeholder */
    public boolean resetsHour() {
        return resetsHour;
    }
}
