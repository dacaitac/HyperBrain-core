package com.hyperbrain.planner.domain.model;

/**
 * The commands a user can issue against the planner through the {@code user-commands.fifo}
 * channel (HU-01b slice 2): a manual replan («calcular» button) and a manual Sleep Score input.
 */
public enum UserCommandType {

    /** Regenerate today's agenda from the current instant (replan-from-now). */
    REPLAN_AGENDA,

    /** Record the user's self-reported sleep score for a given day. */
    SLEEP_SCORE
}
