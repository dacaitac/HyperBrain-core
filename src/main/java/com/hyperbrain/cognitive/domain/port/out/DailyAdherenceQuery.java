package com.hyperbrain.cognitive.domain.port.out;

import com.hyperbrain.cognitive.domain.model.CoachSignals;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only query port into the planner's daily adherence projection ({@code plnr_daily_rollup}, ADR-025
 * D4). The cognitive coach voice depends on this abstraction, never on the planner's infrastructure: the
 * projection single-sources the {@code AdherenceCalculator} formula, and this port lifts the latest day's
 * hard signals (plus the derived WIG streak) into a cognitive-owned {@link CoachSignals}. It is strictly a
 * read — cognitive never mutates another module's state (ArchUnit).
 */
public interface DailyAdherenceQuery {

    /**
     * The most recent rolled-up day's hard signals for a user, or empty when no rollup exists yet.
     *
     * @param userId the user to read; never null
     * @return the latest day's signals, or {@link Optional#empty()} when there is nothing rolled up
     */
    Optional<CoachSignals> latestSignals(UUID userId);
}
