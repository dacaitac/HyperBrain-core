package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;
import com.hyperbrain.planner.domain.model.SlotPurpose;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/*
 * Design pattern: single-algorithm domain service (pure function of its inputs).
 * Reason: the band a block may be retimed within must be reproducible from the same day, the same
 * template and the same wake — the guard that rejects a proposal and the prompt that states the rule
 * to the model have to be looking at exactly the same geometry, or a day degrades for a rule the
 * model was never told.
 */

/**
 * Resolves, for one concrete day, the {@link RetimingBand} each band of the day confines its block to
 * (Daniel, 2026-08-07). One rule, two sources of width:
 *
 * <ul>
 *   <li><b>An ordinary band</b> is its {@link com.hyperbrain.planner.domain.model.TemplateSlot}
 *       resolved onto the day — displaced by the real wake like every window (ADR-040 D2) — and
 *       nothing more. Tolerance zero: «Casa» is the evening.</li>
 *   <li><b>A meal band</b> is that same band <em>widened</em> to the plausible hours of the meal it
 *       holds ({@link MealWindow}), because a meal legitimately floats around its hour while nothing
 *       makes it plausible at any hour. The meal is matched to the band it actually sits in — by
 *       overlap on the day, never by name — so moving either one in configuration keeps them paired.</li>
 * </ul>
 *
 * <p>Every band is finally clamped to the run's planning bounds, so a band can never authorize a move
 * into hours the run is not planning: the past on a replan, or anything past bedtime.
 *
 * <p><b>The widening only ever widens.</b> A band narrower than the window the floor laid would put
 * the floor's own block outside its band and degrade every day for a placement the model never made,
 * so the meal band is a union with the slot's band and never a replacement of it.
 *
 * <p>Bands are keyed by <b>slot id</b>, the same key the block's identity is anchored to (ADR-040
 * D7/D14) — never by hour, which is precisely what moves.
 */
public class RetimingBandResolver {

    private final DayTemplate template;
    private final HumanizationSettings humanization;

    /**
     * @param template     the day's shape; never null
     * @param humanization the floor's calibration, carrying the meal anchors and their bands; never null
     */
    public RetimingBandResolver(DayTemplate template, HumanizationSettings humanization) {
        if (template == null || humanization == null) {
            throw new IllegalArgumentException("template and humanization settings must not be null");
        }
        this.template = template;
        this.humanization = humanization;
    }

    /**
     * Resolves the day's bands.
     *
     * @param targetDay  the calendar day being planned; never null
     * @param zone       the user's timezone; never null
     * @param wake       the real wake instant the template rides on; never null
     * @param lowerBound the earliest instant this run may place at (wake, or now on a replan); never null
     * @param upperBound the bedtime edge; never null
     * @return slot id → the band a block of that slot may be retimed within; never null, immutable,
     *         and carrying no entry for a band that lies entirely outside the planning bounds
     */
    public Map<String, RetimingBand> resolve(LocalDate targetDay, ZoneId zone, OffsetDateTime wake,
                                             OffsetDateTime lowerBound, OffsetDateTime upperBound) {
        if (targetDay == null || zone == null || wake == null
            || lowerBound == null || upperBound == null) {
            throw new IllegalArgumentException("day, zone, wake and bounds must not be null");
        }
        Map<String, RetimingBand> bands = new LinkedHashMap<>();
        for (DayWindow window : template.resolve(targetDay, zone, wake)) {
            RetimingBand band = new RetimingBand(window.slot().label(), window.start(), window.end());
            if (window.slot().purpose() == SlotPurpose.MEAL) {
                band = widenedToMeal(band, window, targetDay, zone);
            }
            RetimingBand bounded = band.clampedTo(lowerBound, upperBound);
            if (bounded != null) {
                bands.put(window.slotId(), bounded);
            }
        }
        return Map.copyOf(bands);
    }

    /**
     * The band widened to the plausible hours of the meal that sits in it, or the band untouched when
     * no configured meal falls in this window (a MEAL band the user has not configured a meal for is
     * simply a band like any other).
     */
    private RetimingBand widenedToMeal(RetimingBand band, DayWindow window, LocalDate targetDay,
                                       ZoneId zone) {
        for (MealWindow meal : humanization.mealWindows()) {
            OccupiedInterval anchor = meal.toWall(targetDay, zone);
            if (anchor.overlaps(window.start(), window.end())) {
                RetimingBand plausible = meal.toBand(targetDay, zone);
                return band.spanning(plausible.start(), plausible.end());
            }
        }
        return band;
    }
}
