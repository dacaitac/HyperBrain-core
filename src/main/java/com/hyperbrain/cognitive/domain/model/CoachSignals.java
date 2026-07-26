package com.hyperbrain.cognitive.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The hard signals of the Scoreboard's coach voice (ADR-029 D3): the confrontable facts of the most
 * recent settled day, read from the {@code plnr_daily_rollup} projection (ADR-025 D4) that single-sources
 * the {@code AdherenceCalculator} formula. The coach voice is anchored strictly to these — never to free
 * prose — so a 4DX coach confronts the gap between the committed lead measure (executing the WIG) and the
 * observed behavior, with cadence (4DX Discipline 4).
 *
 * @param userId    the user the signals belong to; never null
 * @param date      the local day the signals cover (the latest rolled-up day); never null
 * @param wigHit    whether the reserved WIG block was executed that day
 * @param abandoned whether the day was let go (low adherence with zero replans)
 * @param wigStreak consecutive most-recent days, ending at {@code date}, where the WIG was hit; &ge; 0
 * @param adherence the executed fraction of the day's planner blocks (0..1)
 */
public record CoachSignals(
    UUID userId,
    LocalDate date,
    boolean wigHit,
    boolean abandoned,
    int wigStreak,
    double adherence
) {
    public CoachSignals {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        if (wigStreak < 0) {
            throw new IllegalArgumentException("wigStreak must be >= 0: " + wigStreak);
        }
    }
}
