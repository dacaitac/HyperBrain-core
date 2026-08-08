package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.RetimingBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RetimingBandResolver — the band each block may be retimed within")
class RetimingBandResolverTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 8);

    private final RetimingBandResolver resolver =
        new RetimingBandResolver(DayTemplate.DEFAULT, HumanizationSettings.DEFAULT);

    @Test
    @DisplayName("an ordinary band is its own hours: the household band is the evening, nothing more")
    void an_ordinary_band_is_its_own_hours() {
        // When: up at the template's anchor hour
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        // Then: «Casa» spans exactly 19:00–21:00 — the production case (moved to 07:00) is outside it
        RetimingBand household = bands.get("HOUSEHOLD");
        assertThat(household.label()).isEqualTo("Casa");
        assertThat(household.start()).isEqualTo(at(19, 0));
        assertThat(household.end()).isEqualTo(at(21, 0));
        assertThat(household.contains(at(7, 0), at(9, 0))).isFalse();
    }

    @Test
    @DisplayName("a band rides the real wake, like the window it bounds")
    void a_band_rides_the_real_wake() {
        // When: up at 10:00, three hours after the template's anchor
        Map<String, RetimingBand> bands = resolve(at(10, 0), at(10, 0), at(23, 0));

        // Then: the goal band slides with the day instead of demanding the same hour every morning
        assertThat(bands.get("GOAL_MORNING").start()).isEqualTo(at(11, 30));
        assertThat(bands.get("GOAL_MORNING").end()).isEqualTo(at(13, 30));
    }

    @Test
    @DisplayName("a meal band is widened to the meal's plausible hours, never narrowed")
    void a_meal_band_is_widened_to_the_plausible_hours() {
        // Given: the LUNCH band is 13:00–14:00 and the lunch anchor (12:30–13:30) sits in it, plausible
        // between 11:30 and 14:30.
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        // Then: the meal may float across its plausible hours — and the band still covers its own
        // window, so the floor's own placement can never fall outside it.
        RetimingBand lunch = bands.get("LUNCH");
        assertThat(lunch.start()).isEqualTo(at(11, 30));
        assertThat(lunch.end()).isEqualTo(at(14, 30));
        assertThat(lunch.contains(at(13, 0), at(14, 0))).isTrue();
        assertThat(lunch.contains(at(15, 0), at(15, 30))).isFalse();
    }

    @Test
    @DisplayName("a meal band whose meal is unconfigured stays a band like any other")
    void an_unconfigured_meal_band_is_not_widened() {
        // Given: a user who configured no meals at all
        RetimingBandResolver bare =
            new RetimingBandResolver(DayTemplate.DEFAULT, HumanizationSettings.NO_OP);

        // When
        RetimingBand lunch = bare.resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0)).get("LUNCH");

        // Then: no widening was invented for it
        assertThat(lunch.start()).isEqualTo(at(13, 0));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("a meal is matched to the band it sits in, never by name")
    void a_meal_is_matched_by_overlap() {
        // Given: a meal labelled nothing like the band, but landing inside the LUNCH band's hours
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("comida", LocalTime.of(13, 15), LocalTime.of(13, 45),
                LocalTime.of(12, 0), LocalTime.of(15, 0))),
            0.10);

        // When
        RetimingBand lunch = new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0)).get("LUNCH");

        // Then: moving either one in configuration keeps them paired
        assertThat(lunch.start()).isEqualTo(at(12, 0));
        assertThat(lunch.end()).isEqualTo(at(15, 0));
    }

    @Test
    @DisplayName("every band is clamped to the planning bounds, so none authorizes a move into the past")
    void bands_are_clamped_to_the_planning_bounds() {
        // When: a replan at 14:00 on a day whose bedtime is 22:00
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(14, 0), at(22, 0));

        // Then: the morning bands are gone entirely and the band the replan lands in starts at now
        assertThat(bands).doesNotContainKeys("GOAL_MORNING", "WORK_MORNING");
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(14, 0));
        assertThat(bands.get("WIND_DOWN").end()).isEqualTo(at(22, 0));
    }

    @Test
    @DisplayName("the agenda reflections carry a band too — a block born in one is bounded like any other")
    void agenda_reflections_carry_a_band() {
        // The resolver bounds movement; deciding what the floor may fill is the window resolver's job,
        // and the two must not be confused.
        assertThat(resolve(at(7, 0), at(7, 0), at(22, 0))).containsKey("DAILY_STANDUP");
    }

    @Test
    @DisplayName("a meal narrower than the band it sits in never narrows it — the widening only widens")
    void a_narrower_meal_never_narrows_its_band() {
        // Given: a meal whose plausible band (13:15–13:45) is strictly INSIDE the LUNCH band
        // (13:00–14:00). Taking it as the band would leave the floor's own window — the whole
        // 13:00–14:00 slot — outside its band, and every day would degrade over a placement the model
        // never made. This is the load-bearing half of "widen, never narrow".
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 15), LocalTime.of(13, 45),
                LocalTime.of(13, 15), LocalTime.of(13, 45))),
            0.10);

        // When
        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        // Then: the slot's own hours survive whole
        assertThat(lunch.start()).isEqualTo(at(13, 0));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
        assertThat(lunch.contains(at(13, 0), at(14, 0))).isTrue();
    }

    @Test
    @DisplayName("a meal that reaches out on one side only widens that side")
    void a_one_sided_meal_widens_one_side_only() {
        // Given: lunch may start as early as 11:30 but never run past 13:45 — earlier than the LUNCH
        // band's own 14:00 end.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(13, 45),
                LocalTime.of(11, 30), LocalTime.of(13, 45))),
            0.10);

        // When
        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        // Then: the union of both, edge by edge
        assertThat(lunch.start()).isEqualTo(at(11, 30));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("a meal straddling two bands widens only the meal band it sits in, never its neighbour")
    void a_meal_crossing_a_band_edge_leaves_the_neighbour_untouched() {
        // Given: the sanctioned lunch anchor (12:30–13:30) straddles WORK_MORNING (11:00–13:00) and
        // LUNCH (13:00–14:00).
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        // Then: «Oficio» stays exactly the work band — a work block may not slide into the afternoon
        // just because the meal it borders may.
        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(11, 0));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(13, 0));
        // And the meal band it overlaps did widen, so the two are genuinely judged apart.
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(11, 30));
    }

    @Test
    @DisplayName("only a MEAL band widens: a meal anchored in an ordinary band leaves that band alone")
    void a_meal_anchored_in_an_ordinary_band_does_not_widen_it() {
        // The sanctioned breakfast (07:00–07:30, plausible 05:30–10:00) sits in PERSONAL_ROUTINE and
        // the sanctioned dinner (19:00–20:00, plausible 18:00–21:30) sits in HOUSEHOLD — neither slot
        // carries the MEAL purpose, so neither band is widened. Tolerance for an ordinary band is zero
        // by decision, and this is what that costs: those two plausible bands are inert under the
        // sanctioned template. Only a slot whose purpose is MEAL can float.
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        assertThat(bands.get("PERSONAL_ROUTINE").start()).isEqualTo(at(7, 0));
        assertThat(bands.get("PERSONAL_ROUTINE").end()).isEqualTo(at(8, 0));
        assertThat(bands.get("HOUSEHOLD").start()).isEqualTo(at(19, 0));
        assertThat(bands.get("HOUSEHOLD").end()).isEqualTo(at(21, 0));
    }

    @Test
    @DisplayName("a meal merely touching a band's edge does not widen it — the overlap is half-open")
    void a_meal_touching_the_edge_does_not_widen() {
        // Given: a meal ending exactly when the LUNCH band opens. Sharing an instant is not sitting in
        // the band, so nothing about that meal may bound a block born in it.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("brunch", LocalTime.of(12, 0), LocalTime.of(13, 0),
                LocalTime.of(10, 0), LocalTime.of(13, 0))),
            0.10);

        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        assertThat(lunch.start()).isEqualTo(at(13, 0));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("a widened meal band is still clamped: nothing floats past bedtime")
    void a_widened_band_is_still_clamped_to_the_bounds() {
        // Given: a late meal band that reaches past the bedtime edge of this run.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(14, 0),
                LocalTime.of(10, 0), LocalTime.of(23, 30))),
            0.10);

        RetimingBand lunch = new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, at(7, 0), at(11, 0), at(22, 0)).get("LUNCH");

        // Then: the plausible hours widen it, and the run's bounds close it again on both sides.
        assertThat(lunch.start()).isEqualTo(at(11, 0));
        assertThat(lunch.end()).isEqualTo(at(22, 0));
    }

    @Test
    @DisplayName("when two meals sit in the same band, the first configured one decides its width")
    void two_meals_in_one_band_are_decided_by_the_first() {
        // Characterization of a configuration nobody should write. The resolver stops at the first meal
        // that overlaps the band, so the second one's plausible hours are ignored rather than unioned.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(
                new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(13, 30),
                    LocalTime.of(12, 0), LocalTime.of(14, 30)),
                new MealWindow("snack", LocalTime.of(13, 30), LocalTime.of(14, 0),
                    LocalTime.of(11, 0), LocalTime.of(17, 0))),
            0.10);

        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        assertThat(lunch.start()).isEqualTo(at(12, 0));
        assertThat(lunch.end()).isEqualTo(at(14, 30));
    }

    @Test
    @DisplayName("every band contains the template band it was drawn from — the widening is a union")
    void every_band_contains_its_template_band() {
        // The invariant the guard leans on, checked against the whole sanctioned template at once and
        // with a pathological meal configuration: a band may only ever be as wide as, or wider than,
        // the slot it names — clipped by nothing but the run's own bounds.
        HumanizationSettings mean = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 10), LocalTime.of(13, 20),
                LocalTime.of(13, 10), LocalTime.of(13, 20))),
            0.10);
        // Bounds wide enough to hold the whole template (06:00–22:00), so the clamping — a legitimate
        // narrowing, and the only one — cannot be mistaken for the widening drifting.
        Map<String, RetimingBand> bands = new RetimingBandResolver(DayTemplate.DEFAULT, mean)
            .resolve(DAY, BOGOTA, at(7, 0), at(6, 0), at(22, 0));

        for (DayWindow window : DayTemplate.DEFAULT.resolve(DAY, BOGOTA, at(7, 0))) {
            assertThat(bands.get(window.slotId()))
                .as("band of %s", window.slotId())
                .isNotNull()
                .satisfies(band -> assertThat(band.contains(window.start(), window.end())).isTrue());
        }
    }

    @Test
    @DisplayName("the same day resolves to the same bands twice, and the map is immutable")
    void the_resolution_is_deterministic_and_immutable() {
        Map<String, RetimingBand> first = resolve(at(7, 0), at(7, 0), at(22, 0));
        Map<String, RetimingBand> second = resolve(at(7, 0), at(7, 0), at(22, 0));

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
        assertThatThrownBy(() -> first.put("X", first.get("LUNCH")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a null template, null settings and any null argument of a resolution")
    void rejects_null_inputs() {
        assertThatThrownBy(() -> new RetimingBandResolver(null, HumanizationSettings.DEFAULT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetimingBandResolver(DayTemplate.DEFAULT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(null, BOGOTA, at(7, 0), at(7, 0), at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, null, at(7, 0), at(7, 0), at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, BOGOTA, null, at(7, 0), at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, BOGOTA, at(7, 0), null, at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, BOGOTA, at(7, 0), at(7, 0), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, RetimingBand> bandsOf(HumanizationSettings settings) {
        return new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0));
    }

    private Map<String, RetimingBand> resolve(OffsetDateTime wake, OffsetDateTime lowerBound,
                                              OffsetDateTime upperBound) {
        return resolver.resolve(DAY, BOGOTA, wake, lowerBound, upperBound);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, LocalTime.of(hour, minute), ZoneOffset.ofHours(-5));
    }
}
