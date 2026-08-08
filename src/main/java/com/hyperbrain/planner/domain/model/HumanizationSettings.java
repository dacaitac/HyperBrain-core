package com.hyperbrain.planner.domain.model;

import java.time.LocalTime;
import java.util.List;

/**
 * What survives of the humanization calibration once ADR-040 D1 has retired the capacity discounts.
 *
 * <p>Four mechanisms used to discount capacity at the same time — a chaos margin that held back a
 * quarter to a third of the day, a five-minute cushion after every block, an occupancy cap and a
 * minimum block duration. Compounded, they left roughly half the day unusable, and nobody chose that:
 * it was the accumulated result of four reasonable decisions taken separately. They are gone. What
 * remains is the rule that must outlive the document: <b>availability is expressed on the time
 * axis</b> — sleep frontier, other people's commitments, protected anchors — and nothing trims the
 * tail of the day.
 *
 * <p>Two calibrations survive because neither discounts anything:
 *
 * @param mealWindows    the protected meal anchors, which enter the run as walls like any other
 *                       commitment and carry the plausible band they may float within; never null, may
 *                       be empty; resolved against the run's zone
 * @param batchBandWidth the priority-score tolerance within which adjacent executables count as
 *                       comparable and may be regrouped by context, so same-context work tends to land
 *                       in the same window — a tie-break inside a band, never a reordering across
 *                       priority bands; default 0.10 (DOMAIN FORMULA, Daniel)
 */
public record HumanizationSettings(List<MealWindow> mealWindows, double batchBandWidth) {

    /**
     * The sanctioned MVP defaults: breakfast, lunch and dinner, each with the plausible band it may
     * float within (Daniel, 2026-08-07), and a 0.10 batching band.
     *
     * <p>The anchors are deliberately short and the bands deliberately wide: the anchor is the time
     * the floor keeps free of work, the band is where that time may plausibly sit. Breakfast is here
     * because it is the meal production got wrong — it was placed at three in the afternoon — and a
     * meal with no anchor has no band to be judged against. Every value is configuration
     * ({@code app.planner.humanize.meals}), so correcting an hour is a settings edit, never a deploy.
     */
    public static final HumanizationSettings DEFAULT = new HumanizationSettings(
        List.of(
            new MealWindow("breakfast", LocalTime.of(7, 0), LocalTime.of(7, 30),
                LocalTime.of(5, 30), LocalTime.of(10, 0)),
            new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
                LocalTime.of(11, 30), LocalTime.of(14, 30)),
            new MealWindow("dinner", LocalTime.of(19, 0), LocalTime.of(20, 0),
                LocalTime.of(18, 0), LocalTime.of(21, 30))),
        0.10);

    /** A no-op instance: no meal walls and no batching — the raw floor. */
    public static final HumanizationSettings NO_OP = new HumanizationSettings(List.of(), 0.0);

    public HumanizationSettings {
        if (batchBandWidth < 0.0 || batchBandWidth > 1.0) {
            throw new IllegalArgumentException("batchBandWidth must be in [0, 1]: " + batchBandWidth);
        }
        mealWindows = mealWindows == null ? List.of() : List.copyOf(mealWindows);
    }
}
