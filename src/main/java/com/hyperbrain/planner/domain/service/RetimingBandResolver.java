package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li><b>A band no meal sits in</b> is its {@link com.hyperbrain.planner.domain.model.TemplateSlot}
 *       resolved onto the day — displaced by the real wake like every window (ADR-040 D2) — and
 *       nothing more. Tolerance zero: «Reuniones» is the afternoon.</li>
 *   <li><b>A band that holds a meal</b> is that same band <em>widened</em> to the meal's plausible
 *       hours ({@link MealWindow}), because a meal legitimately floats around its hour while nothing
 *       makes it plausible at any hour. <b>Whatever that band is for:</b> under the sanctioned
 *       template breakfast lives inside «Rutina personal» and dinner inside «Casa», and only lunch
 *       has a band of its own — keying the widening on the slot's purpose would have left the
 *       configured hours of two meals out of three inert. The meal is matched to the band it
 *       actually sits in — by overlap on the day, never by name — so moving either one in
 *       configuration keeps them paired.</li>
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
        List<DayWindow> windows = template.resolve(targetDay, zone, wake);
        Map<String, RetimingBand> plausibleHours = plausibleHoursBySlot(windows, targetDay, zone);
        Map<String, RetimingBand> bands = new LinkedHashMap<>();
        for (DayWindow window : windows) {
            RetimingBand band = new RetimingBand(window.slot().label(), window.start(), window.end());
            RetimingBand plausible = plausibleHours.get(window.slotId());
            if (plausible != null) {
                band = band.spanning(plausible.start(), plausible.end());
            }
            RetimingBand bounded = band.clampedTo(lowerBound, upperBound);
            if (bounded != null) {
                bands.put(window.slotId(), bounded);
            }
        }
        return Map.copyOf(bands);
    }

    /**
     * The plausible hours each band inherits from the meals that sit in it, keyed by slot id — empty
     * for every band no configured meal lands in, which is then a band like any other.
     *
     * <p>A band holding <b>more than one meal</b> takes the union of their plausible hours: a band
     * narrower than that is a band one of its own meals could be pushed out of, and each meal's hours
     * are a statement about that stretch of the day, not a competing one. Union is the only reading
     * consistent with a widening that never narrows.
     */
    private Map<String, RetimingBand> plausibleHoursBySlot(List<DayWindow> windows, LocalDate targetDay,
                                                           ZoneId zone) {
        Map<String, RetimingBand> plausible = new LinkedHashMap<>();
        for (MealWindow meal : humanization.mealWindows()) {
            DayWindow home = homeOf(meal, windows, targetDay, zone);
            if (home != null) {
                RetimingBand hours = meal.toBand(targetDay, zone);
                plausible.merge(home.slotId(), hours,
                    (held, added) -> held.spanning(added.start(), added.end()));
            }
        }
        return plausible;
    }

    /**
     * The one band a meal sits in — the band it shares the most of itself with — or null when the meal
     * falls outside the template altogether (the day slid, and the meal stayed on the wall clock).
     *
     * <p><b>Why one and not every band it touches.</b> The sanctioned lunch runs 12:30–13:30, across
     * «Oficio» and «Almuerzo». Widening both would let a work block slide into the afternoon on the
     * strength of the meal beside it — the loss of form the band exists to prevent — so the meal widens
     * only the band that holds most of it. The overlap is half-open: a meal that merely ends where a
     * band opens does not sit in it. A meal split evenly between two bands goes to the later one, by
     * that same convention — an instant on the edge belongs to what starts there — which is what pairs
     * the sanctioned lunch with «Almuerzo».
     */
    private static DayWindow homeOf(MealWindow meal, List<DayWindow> windows, LocalDate targetDay,
                                    ZoneId zone) {
        OccupiedInterval anchor = meal.toWall(targetDay, zone);
        DayWindow home = null;
        Duration widest = Duration.ZERO;
        // The windows are chronological, so accepting an equal share hands a tie to the later band.
        for (DayWindow window : windows) {
            Duration shared = sharedTime(anchor, window);
            if (!shared.isZero() && shared.compareTo(widest) >= 0) {
                widest = shared;
                home = window;
            }
        }
        return home;
    }

    /** The time a meal anchor and a window share; zero when they only touch at an edge. */
    private static Duration sharedTime(OccupiedInterval anchor, DayWindow window) {
        OffsetDateTime from = anchor.start().isAfter(window.start()) ? anchor.start() : window.start();
        OffsetDateTime to = anchor.end().isBefore(window.end()) ? anchor.end() : window.end();
        return to.isAfter(from) ? Duration.between(from, to) : Duration.ZERO;
    }
}
