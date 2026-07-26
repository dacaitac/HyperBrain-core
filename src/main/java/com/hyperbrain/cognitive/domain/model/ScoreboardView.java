package com.hyperbrain.cognitive.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * The read model the 4DX Scoreboard renders (ADR-029 D3): the hard adherence signals plus an
 * <b>optional</b> coach voice. The optionality is the invariant — the panel is built from the signals
 * alone and renders with or without the voice, so a slow, failing or not-yet-computed voice never blocks
 * the Scoreboard. The coach voice is attached lazily/asynchronously once available; until then (or if the
 * whole voice path is skipped) the panel stands on its numbers.
 *
 * @param hasData    whether a rolled-up day exists to show (false before the first rollup)
 * @param wigHit     whether the WIG was hit on the shown day
 * @param abandoned  whether the shown day was abandoned
 * @param wigStreak  consecutive WIG-hit days up to the shown day; &ge; 0
 * @param adherence  the executed fraction of the shown day (0..1)
 * @param coachVoice the coach line, when available; never null (use {@link Optional#empty()})
 */
public record ScoreboardView(
    boolean hasData,
    boolean wigHit,
    boolean abandoned,
    int wigStreak,
    double adherence,
    Optional<CoachVoice> coachVoice
) {
    public ScoreboardView {
        Objects.requireNonNull(coachVoice, "coachVoice must not be null (use Optional.empty())");
    }

    /** The no-data Scoreboard: shown before the first daily rollup exists. Renders, carries no voice. */
    public static ScoreboardView empty() {
        return new ScoreboardView(false, false, false, 0, 0.0, Optional.empty());
    }

    /**
     * The base panel from the hard signals alone, <b>without</b> a coach voice — the proof that the
     * Scoreboard renders independently of the voice. Attach the voice later with {@link #withVoice}.
     */
    public static ScoreboardView from(CoachSignals signals) {
        return new ScoreboardView(true, signals.wigHit(), signals.abandoned(), signals.wigStreak(),
            signals.adherence(), Optional.empty());
    }

    /** This panel with the coach voice attached; the numbers are unchanged. */
    public ScoreboardView withVoice(CoachVoice voice) {
        Objects.requireNonNull(voice, "voice must not be null");
        return new ScoreboardView(hasData, wigHit, abandoned, wigStreak, adherence, Optional.of(voice));
    }
}
