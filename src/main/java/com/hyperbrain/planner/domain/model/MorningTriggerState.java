package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;

/**
 * The persisted state of the morning agenda dispatch (HU-01b delivery slice): yesterday's resolved
 * trigger minute-of-day (the hysteresis anchor) and the last calendar day the dispatch actually
 * fired (the once-per-day idempotency guard). Stored per user in {@code sys_user.settings} under
 * {@code planner_state.morning_trigger}; a fresh user carries no state, which the calculator reads
 * as "no anchor yet, no clamp".
 *
 * @param previousTriggerMinuteOfDay the trigger minute-of-day resolved on the previous run, the
 *                                   hysteresis anchor; null when the dispatch has never resolved one
 * @param lastFiredDay               the local calendar day the dispatch last fired; null when it has
 *                                   never fired
 */
public record MorningTriggerState(
    LocalTimeOfDay previousTriggerMinuteOfDay,
    LocalDate lastFiredDay
) {

    /** The empty state for a user whose morning dispatch has never run. */
    public static final MorningTriggerState EMPTY = new MorningTriggerState(null, null);

    /** @return true when a previous trigger minute exists to clamp against */
    public boolean hasAnchor() {
        return previousTriggerMinuteOfDay != null;
    }

    /**
     * Whether the dispatch has already fired for the given local day.
     *
     * @param day the local calendar day under evaluation; never null
     * @return true when {@code lastFiredDay} equals {@code day}
     */
    public boolean firedOn(LocalDate day) {
        return day.equals(lastFiredDay);
    }
}
