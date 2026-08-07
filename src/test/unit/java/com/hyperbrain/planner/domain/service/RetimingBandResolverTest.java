package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
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

    private Map<String, RetimingBand> resolve(OffsetDateTime wake, OffsetDateTime lowerBound,
                                              OffsetDateTime upperBound) {
        return resolver.resolve(DAY, BOGOTA, wake, lowerBound, upperBound);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, LocalTime.of(hour, minute), ZoneOffset.ofHours(-5));
    }
}
