package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;

/**
 * A manual sleep-score input: the user's self-reported score for one calendar day. Carries no
 * bedtime/wake instants on purpose — a manual score must feed the energy resolution (F3/F6)
 * without injecting synthetic hours into the sleep-frontier median.
 *
 * @param score the self-reported sleep score, in {@code [0, 100]}
 * @param date  the local calendar day the score refers to; never null
 */
public record SleepScoreInput(int score, LocalDate date) {

    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    public SleepScoreInput {
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw new IllegalArgumentException(
                "sleep score must be in [" + MIN_SCORE + ", " + MAX_SCORE + "]: " + score);
        }
        if (date == null) {
            throw new IllegalArgumentException("sleep score date is required");
        }
    }
}
