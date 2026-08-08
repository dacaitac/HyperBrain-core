package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one invariant that ties the two resolvers together: <b>every window the floor lays sits inside
 * the band of its own slot</b>.
 *
 * <p>It is what makes the band a safe wall to judge the intelligent layer by. The guard only checks a
 * {@code MOVE}, so a floor placement that fell outside its band would not degrade a day on its own —
 * but the model would then be told a rule it cannot obey without moving the block off the very hours
 * the floor chose, and any proposal that keeps a block roughly where the floor put it would be
 * rejected. The day would degrade for a placement nobody made.
 *
 * <p>The invariant holds by construction — a band is the template slot clamped to the run's bounds,
 * while the window is that same slot clamped to the same bounds <em>and then</em> clipped by the walls,
 * so the window is always a sub-interval of the band — and this test pins it against the cases where
 * the two computations could drift apart: a displaced wake, a mid-day replan, hard walls eating into a
 * band, and a meal whose plausible hours are narrower than the band it sits in.
 *
 * <p><b>Why the sweeps.</b> Meals keep their civil hour while the template rides the real wake, so
 * every displacement of the day re-pairs the meals with different bands — the pairing is not a fixed
 * property of the configuration. Arguing that no pairing can produce a band narrower than its own
 * slot's window is not the same as showing it, so the displacement, the replan hour and the DST
 * transitions are swept rather than sampled.
 */
@DisplayName("Bands vs the floor's windows — the floor never places outside a band")
class RetimingBandFloorInvariantTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 8);

    @Test
    @DisplayName("an ordinary day: every window laid falls inside its band")
    void an_ordinary_day_holds_the_invariant() {
        assertInvariant(HumanizationSettings.DEFAULT, at(7, 0), at(7, 0), at(22, 0), List.of());
    }

    @Test
    @DisplayName("a late wake displaces windows and bands by the same shift")
    void a_late_wake_holds_the_invariant() {
        assertInvariant(HumanizationSettings.DEFAULT, at(10, 30), at(10, 30), at(23, 0), List.of());
    }

    @Test
    @DisplayName("a mid-day replan clamps windows and bands against the same lower bound")
    void a_replan_holds_the_invariant() {
        assertInvariant(HumanizationSettings.DEFAULT, at(7, 0), at(14, 20), at(22, 0), List.of());
    }

    @Test
    @DisplayName("hard walls clip the windows and never the bands, so the windows only shrink inwards")
    void walled_bands_hold_the_invariant() {
        List<OccupiedInterval> walls = List.of(
            new OccupiedInterval(UUID.randomUUID(), at(9, 15), at(9, 45), true),
            new OccupiedInterval(UUID.randomUUID(), at(11, 0), at(12, 15), false),
            new OccupiedInterval(UUID.randomUUID(), at(19, 30), at(20, 45), true));

        assertInvariant(HumanizationSettings.DEFAULT, at(7, 0), at(7, 0), at(22, 0), walls);
    }

    @Test
    @DisplayName("a meal narrower than the band it sits in cannot pull the band off the floor's window")
    void a_narrow_meal_holds_the_invariant() {
        // The case the "widen, never narrow" rule exists for: taken as a replacement, this meal's band
        // would be 13:20–13:40 while the floor lays the whole 13:00–14:00 lunch window.
        HumanizationSettings narrow = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 20), LocalTime.of(13, 40),
                LocalTime.of(13, 20), LocalTime.of(13, 40))),
            0.10);

        assertInvariant(narrow, at(7, 0), at(7, 0), at(22, 0), List.of());
    }

    @Test
    @DisplayName("an early wake slides the template under the meals, which widen whatever band they land in")
    void an_early_wake_holds_the_invariant() {
        // The meals stay on the wall clock while the template slides, so on a displaced day a meal
        // lands in a band it does not normally sit in (breakfast at 07:00 falls in the standup once the
        // day starts at 06:15). Three bands are widened at once and the widening only ever widens, so
        // every window stays inside its band and the guard may keep judging a MOVE by the band alone.
        assertInvariant(HumanizationSettings.DEFAULT, at(6, 15), at(6, 15), at(21, 30), List.of());
    }

    @Test
    @DisplayName("two meals sharing one band union their hours without pulling the band off the window")
    void two_meals_in_one_band_hold_the_invariant() {
        HumanizationSettings twoInOne = new HumanizationSettings(
            List.of(
                new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(13, 30),
                    LocalTime.of(12, 0), LocalTime.of(14, 0)),
                new MealWindow("snack", LocalTime.of(13, 30), LocalTime.of(13, 50),
                    LocalTime.of(13, 30), LocalTime.of(13, 50))),
            0.10);

        assertInvariant(twoInOne, at(7, 0), at(7, 0), at(22, 0), List.of());
    }

    @ParameterizedTest(name = "up at minute {0} of the day")
    @MethodSource("wakeMinutesAcrossTheMorning")
    @DisplayName("however far the day is displaced, no window the floor lays falls outside its band")
    void any_displacement_holds_the_invariant(int wakeMinute) {
        // The grave failure mode this rules out. Every quarter of an hour of displacement re-pairs the
        // three sanctioned meals with different bands — breakfast walks from the wake margin through
        // the routine, the stand-up and the goal window; lunch from the work band through the lunch
        // band to the whirlwind — and each pairing widens a different band. If any one of them could
        // yield a band narrower than the window of its own slot, the guard would start degrading days
        // over placements the model never made. Swept, not argued.
        OffsetDateTime wake = atMinute(wakeMinute);
        assertInvariant(HumanizationSettings.DEFAULT, wake, wake, wake.plusHours(16), List.of());
    }

    @ParameterizedTest(name = "up at minute {0} of the day, with three commitments already standing")
    @MethodSource("wakeMinutesAcrossTheMorning")
    @DisplayName("a displaced day whose windows are also eaten by hard walls still holds the invariant")
    void any_displacement_with_walls_holds_the_invariant(int wakeMinute) {
        // The two mechanisms that could conceivably interact: a wall clips the window (never the band)
        // while the displacement decides which band a meal widens. The walls ride the wake so they keep
        // biting into the same part of the day at every displacement instead of drifting off the end.
        OffsetDateTime wake = atMinute(wakeMinute);
        List<OccupiedInterval> walls = List.of(
            new OccupiedInterval(wallId(1), wake.plusMinutes(95), wake.plusMinutes(150), true),
            new OccupiedInterval(wallId(2), wake.plusMinutes(310), wake.plusMinutes(395), false),
            new OccupiedInterval(wallId(3), wake.plusMinutes(640), wake.plusMinutes(700), true));

        assertInvariant(HumanizationSettings.DEFAULT, wake, wake, wake.plusHours(16), walls);
    }

    @ParameterizedTest(name = "replanned at minute {0} of the day")
    @MethodSource("replanMinutesAcrossTheDay")
    @DisplayName("a replan issued at any hour clamps windows and bands alike")
    void any_replan_hour_holds_the_invariant(int replanMinute) {
        // A replan moves only the lower bound, and both resolvers clamp against it independently — the
        // window resolver on the raw slot, the band resolver on the already-widened band. A widened
        // band clamps to a different instant than its slot would, which is exactly where the two
        // computations could drift apart, so every half hour of the day is swept.
        assertInvariant(HumanizationSettings.DEFAULT, at(7, 0), atMinute(replanMinute), at(22, 0),
            List.of());
    }

    @Test
    @DisplayName("a DST day, where the meals and the template stop agreeing on the hour, still holds it")
    void a_dst_day_holds_the_invariant() {
        // The template is laid by adding minutes to the day's first instant, while a meal is resolved
        // as a civil hour — the offset in force at that hour. On an ordinary day both are anchored to
        // the same wake and agree; on a day whose transition falls between midnight and the end of the
        // run they do not, and the meals land on bands they never normally touch. Bogotá has no DST, so
        // this is not the MVP's day — it is the day that proves the invariant does not quietly depend
        // on the two geometries agreeing.
        ZoneId madrid = ZoneId.of("Europe/Madrid");
        LocalDate springForward = LocalDate.of(2026, 3, 29);
        LocalDate fallBack = LocalDate.of(2026, 10, 25);

        assertInvariantOn(madrid, springForward, LocalTime.of(7, 0), LocalTime.of(7, 0),
            LocalTime.of(22, 0));
        assertInvariantOn(madrid, fallBack, LocalTime.of(7, 0), LocalTime.of(7, 0),
            LocalTime.of(22, 0));
        // And the same two days planned from before the transition, so the run itself straddles it.
        assertInvariantOn(madrid, springForward, LocalTime.of(1, 0), LocalTime.of(1, 0),
            LocalTime.of(20, 0));
        assertInvariantOn(madrid, fallBack, LocalTime.of(1, 0), LocalTime.of(1, 0),
            LocalTime.of(20, 0));
    }

    /** Every quarter of an hour from four in the morning to noon — the whole plausible wake range. */
    static IntStream wakeMinutesAcrossTheMorning() {
        return IntStream.iterate(4 * 60, minute -> minute <= 12 * 60, minute -> minute + 15);
    }

    /** Every half hour a replan could plausibly be triggered at, on a day that ends at 22:00. */
    static IntStream replanMinutesAcrossTheDay() {
        return IntStream.iterate(7 * 60, minute -> minute <= 21 * 60, minute -> minute + 30);
    }

    /**
     * Lays the day exactly as the generation service does — meal anchors folded into the walls, both
     * resolvers reading the same day, zone, wake and bounds — and asserts every surviving window is
     * contained in the band of its slot.
     */
    private static void assertInvariant(HumanizationSettings settings, OffsetDateTime wake,
                                        OffsetDateTime lowerBound, OffsetDateTime upperBound,
                                        List<OccupiedInterval> extraWalls) {
        assertInvariantOn(BOGOTA, DAY, settings, wake, lowerBound, upperBound, extraWalls);
    }

    /** The same assertion on any zone and day, stated in the user's own civil hours. */
    private static void assertInvariantOn(ZoneId zone, LocalDate day, LocalTime wake,
                                          LocalTime lowerBound, LocalTime upperBound) {
        assertInvariantOn(zone, day, HumanizationSettings.DEFAULT, civil(day, zone, wake),
            civil(day, zone, lowerBound), civil(day, zone, upperBound), List.of());
    }

    private static void assertInvariantOn(ZoneId zone, LocalDate day, HumanizationSettings settings,
                                          OffsetDateTime wake, OffsetDateTime lowerBound,
                                          OffsetDateTime upperBound,
                                          List<OccupiedInterval> extraWalls) {
        List<OccupiedInterval> walls = new ArrayList<>(extraWalls);
        settings.mealWindows().forEach(meal -> walls.add(meal.toWall(day, zone)));

        List<DayWindow> windows = new DayWindowResolver(DayTemplate.DEFAULT)
            .resolve(day, zone, wake, lowerBound, upperBound, walls);
        Map<String, RetimingBand> bands = new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(day, zone, wake, lowerBound, upperBound);

        assertThat(windows).as("windows laid on %s from %s", day, wake).isNotEmpty();
        assertThat(windows).allSatisfy(window -> {
            RetimingBand band = bands.get(window.slotId());
            assertThat(band).as("band of %s", window.slotId()).isNotNull();
            assertThat(band.contains(window.start(), window.end()))
                .as("%s window %s..%s inside band %s..%s",
                    window.slotId(), window.start(), window.end(), band.start(), band.end())
                .isTrue();
        });
    }

    private static OffsetDateTime civil(LocalDate day, ZoneId zone, LocalTime time) {
        return day.atTime(time).atZone(zone).toOffsetDateTime();
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, LocalTime.of(hour, minute), ZoneOffset.ofHours(-5));
    }

    private static OffsetDateTime atMinute(int minuteOfDay) {
        return OffsetDateTime.of(DAY, LocalTime.MIDNIGHT, ZoneOffset.ofHours(-5))
            .plusMinutes(minuteOfDay);
    }

    /** A stable wall identity, so a swept case reproduces byte for byte on a rerun. */
    private static UUID wallId(int index) {
        return UUID.fromString("00000000-0000-4000-8000-00000000000" + index);
    }
}
