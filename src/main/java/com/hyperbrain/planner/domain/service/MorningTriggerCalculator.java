package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MorningTriggerState;
import com.hyperbrain.planner.domain.model.SleepWindow;

/**
 * Pure domain service that resolves the wall-clock minute-of-day at which the morning agenda dispatch
 * fires (HU-01b delivery slice): the observed wake edge plus a fixed lead offset, clamped to move no
 * more than a hysteresis margin per day relative to yesterday's resolved trigger.
 *
 * <p><b>Why the offset.</b> The dispatch runs a short while after the estimated wake so the day is
 * planned against a settled frontier and the agenda is ready when the user picks up the phone.
 *
 * <p><b>Why the clamp.</b> The circular median of the wake edge over ~5 nights carries an error of
 * roughly ±40 min, so an unclamped trigger would oscillate day to day and could drift across the
 * 14-day validation window. Bounding the day-to-day movement to {@code hysteresisMarginMinutes}
 * relative to the previous trigger (the {@code applyHysteresisAtCut} pattern of
 * {@link WigPortfolioSelector}) keeps the fire time stable while still tracking a real shift in the
 * frontier over several days. On cold start (no previous trigger) the raw wake+offset is used as is.
 *
 * <p>The service works modulo {@link LocalTimeOfDay#MINUTES_PER_DAY}; the clamp measures the signed
 * shortest-arc distance on the 24h ring so a trigger near the midnight seam moves along the shorter
 * side, never the long way around.
 */
public class MorningTriggerCalculator {

    private static final int HALF_DAY = LocalTimeOfDay.MINUTES_PER_DAY / 2;

    private final int leadOffsetMinutes;
    private final int hysteresisMarginMinutes;

    /**
     * Creates a calculator with the dispatch lead offset and hysteresis margin.
     *
     * @param leadOffsetMinutes       minutes after the wake edge at which the dispatch fires; must be
     *                                non-negative
     * @param hysteresisMarginMinutes the maximum day-to-day move of the trigger relative to yesterday;
     *                                must be non-negative
     */
    public MorningTriggerCalculator(int leadOffsetMinutes, int hysteresisMarginMinutes) {
        if (leadOffsetMinutes < 0) {
            throw new IllegalArgumentException("leadOffsetMinutes must be non-negative: " + leadOffsetMinutes);
        }
        if (hysteresisMarginMinutes < 0) {
            throw new IllegalArgumentException(
                "hysteresisMarginMinutes must be non-negative: " + hysteresisMarginMinutes);
        }
        this.leadOffsetMinutes = leadOffsetMinutes;
        this.hysteresisMarginMinutes = hysteresisMarginMinutes;
    }

    /**
     * Resolves today's trigger minute-of-day from the sleep frontier and the persisted state.
     *
     * @param sleepWindow the resolved sleep frontier (observed or cold-start); never null
     * @param state       the persisted trigger state carrying yesterday's anchor; never null
     * @return the clamped trigger minute-of-day for today
     */
    public LocalTimeOfDay resolveTrigger(SleepWindow sleepWindow, MorningTriggerState state) {
        int rawTrigger = Math.floorMod(
            sleepWindow.wakeEstimate().minutesOfDay() + leadOffsetMinutes,
            LocalTimeOfDay.MINUTES_PER_DAY);
        if (!state.hasAnchor()) {
            return new LocalTimeOfDay(rawTrigger);
        }
        int anchor = state.previousTriggerMinuteOfDay().minutesOfDay();
        int clamped = clampToMargin(rawTrigger, anchor);
        return new LocalTimeOfDay(clamped);
    }

    /**
     * Clamps {@code target} to lie within {@code ±hysteresisMarginMinutes} of {@code anchor} on the
     * 24h ring, measuring the signed shortest-arc distance so the trigger never jumps the long way
     * around the midnight seam.
     *
     * @param target the raw trigger minute-of-day
     * @param anchor the previous trigger minute-of-day
     * @return the clamped minute-of-day wrapped back onto {@code [0, 1440)}
     */
    private int clampToMargin(int target, int anchor) {
        int signedDelta = shortestArc(target - anchor);
        int boundedDelta = Math.max(-hysteresisMarginMinutes, Math.min(hysteresisMarginMinutes, signedDelta));
        return Math.floorMod(anchor + boundedDelta, LocalTimeOfDay.MINUTES_PER_DAY);
    }

    /**
     * Reduces a raw minute difference to its signed shortest-arc value in {@code (-720, 720]}.
     *
     * @param delta the raw {@code target - anchor} difference
     * @return the equivalent difference on the shorter side of the ring
     */
    private static int shortestArc(int delta) {
        int wrapped = Math.floorMod(delta, LocalTimeOfDay.MINUTES_PER_DAY);
        return wrapped > HALF_DAY ? wrapped - LocalTimeOfDay.MINUTES_PER_DAY : wrapped;
    }
}
