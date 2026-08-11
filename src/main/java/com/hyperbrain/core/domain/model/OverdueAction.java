package com.hyperbrain.core.domain.model;

/**
 * What the day-close sweep does with an executable whose window fell into a past local day
 * (ADR-040 D4). One value per distinct outcome of the seven-row dispatch table; the mapping
 * from {@code core_executable.type} to an action lives in {@link OverduePolicy}.
 */
public enum OverdueAction {

    /**
     * The executable leaves the inventory as a sanctioned miss ({@code FAILED}). It stops being
     * schedulable, its streak resets, its completion clock is stamped and — when it carries a
     * frequency — DR-04 schedules the next occurrence.
     */
    CLOSE_AS_FAILED,

    /**
     * The executable is <b>moved</b> to the sweep's reference day instead of being closed: same
     * row, new date. This is what makes an undone item a candidate again, since the admission rule
     * only admits today's items and the dateless ones (ADR-040 D3).
     */
    RESCHEDULE,

    /**
     * The executable loses its date and returns to the dateless bag, from which it may enter any
     * day. A shopping list has no day.
     */
    CLEAR_DATE,

    /** The sweep does not act on this type: something else owns its closure. */
    NONE
}
