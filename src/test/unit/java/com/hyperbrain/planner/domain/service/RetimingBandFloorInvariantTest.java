package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * Lays the day exactly as the generation service does — meal anchors folded into the walls, both
     * resolvers reading the same day, zone, wake and bounds — and asserts every surviving window is
     * contained in the band of its slot.
     */
    private static void assertInvariant(HumanizationSettings settings, OffsetDateTime wake,
                                        OffsetDateTime lowerBound, OffsetDateTime upperBound,
                                        List<OccupiedInterval> extraWalls) {
        List<OccupiedInterval> walls = new ArrayList<>(extraWalls);
        settings.mealWindows().forEach(meal -> walls.add(meal.toWall(DAY, BOGOTA)));

        List<DayWindow> windows = new DayWindowResolver(DayTemplate.DEFAULT)
            .resolve(DAY, BOGOTA, wake, lowerBound, upperBound, walls);
        Map<String, RetimingBand> bands = new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, wake, lowerBound, upperBound);

        assertThat(windows).isNotEmpty();
        assertThat(windows).allSatisfy(window -> {
            RetimingBand band = bands.get(window.slotId());
            assertThat(band).as("band of %s", window.slotId()).isNotNull();
            assertThat(band.contains(window.start(), window.end()))
                .as("%s window %s..%s inside band %s..%s",
                    window.slotId(), window.start(), window.end(), band.start(), band.end())
                .isTrue();
        });
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, LocalTime.of(hour, minute), ZoneOffset.ofHours(-5));
    }
}
