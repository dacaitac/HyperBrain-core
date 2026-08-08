package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.service.RetimingBandResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The configuration seam of the meal anchors and their plausible bands ({@code app.planner.humanize}).
 *
 * <p>Two things are worth a test here and neither is obvious. First, the <b>relaxed binding</b> of
 * {@code band-start}/{@code band-end}: a typo in those keys does not fail — it leaves the edges null
 * and the meal silently becomes rigid, which is a feature quietly switching itself off. Second, the
 * <b>fail-fast</b>: a band that does not enclose its own anchor must abort the context at bean
 * creation rather than let the planner run against a band the floor's own meal cannot sit in.
 */
@DisplayName("HumanizationProperties — the meal anchors and their plausible bands, as configured")
class HumanizationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PlannerConfig.class)
        .withBean(PlannerConstantsLoader.class, HumanizationPropertiesTest::stubLoader)
        .withPropertyValues(
            "app.planner.delivery.lead-offset-minutes=10",
            "app.planner.delivery.hysteresis-margin-minutes=15",
            "app.planner.delivery.trigger-tolerance-minutes=5");

    @Test
    @DisplayName("a configured band is bound and carried through to the domain settings")
    void a_configured_band_is_bound() {
        // Given: the shape the sanctioned application.yml uses, kebab-cased.
        runner
            .withPropertyValues(
                "app.planner.humanize.meals[0].label=breakfast",
                "app.planner.humanize.meals[0].start=07:00",
                "app.planner.humanize.meals[0].end=07:30",
                "app.planner.humanize.meals[0].band-start=05:30",
                "app.planner.humanize.meals[0].band-end=10:00")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(HumanizationSettings.class).mealWindows())
                    .singleElement()
                    .satisfies(meal -> {
                        assertThat(meal.label()).isEqualTo("breakfast");
                        assertThat(meal.start()).isEqualTo(LocalTime.of(7, 0));
                        assertThat(meal.end()).isEqualTo(LocalTime.of(7, 30));
                        assertThat(meal.bandStart()).isEqualTo(LocalTime.of(5, 30));
                        assertThat(meal.bandEnd()).isEqualTo(LocalTime.of(10, 0));
                    });
                // And the band resolver is wired from those same settings, not from the defaults.
                assertThat(context).hasSingleBean(RetimingBandResolver.class);
            });
    }

    @Test
    @DisplayName("a band that does not enclose its own anchor aborts the context — it never plans quietly")
    void an_impossible_band_fails_the_context() {
        runner
            .withPropertyValues(
                "app.planner.humanize.meals[0].label=lunch",
                "app.planner.humanize.meals[0].start=12:30",
                "app.planner.humanize.meals[0].end=13:30",
                // Plausible only from 13:00 — the hour the meal is actually configured for is outside
                // its own band, so the floor's own anchor could not sit in it.
                "app.planner.humanize.meals[0].band-start=13:00",
                "app.planner.humanize.meals[0].band-end=14:30")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plausible band must enclose the meal window"));
    }

    @Test
    @DisplayName("a meal configured without a band is rigid rather than unbounded")
    void a_meal_without_a_band_is_rigid() {
        // Absent edges are the honest conservative reading: nothing was said about where else this
        // meal could sit, so it may not float at all.
        HumanizationProperties properties = new HumanizationProperties(
            List.of(new HumanizationProperties.Meal(
                "lunch", LocalTime.of(12, 30), LocalTime.of(13, 30), null, null)),
            0.10);

        assertThat(properties.toSettings().mealWindows())
            .singleElement()
            .satisfies(meal -> {
                assertThat(meal.bandStart()).isEqualTo(LocalTime.of(12, 30));
                assertThat(meal.bandEnd()).isEqualTo(LocalTime.of(13, 30));
            });
    }

    @Test
    @DisplayName("one configured edge is honoured and the other falls back to the anchor's own")
    void a_half_configured_band_falls_back_edge_by_edge() {
        HumanizationProperties properties = new HumanizationProperties(
            List.of(new HumanizationProperties.Meal(
                "lunch", LocalTime.of(12, 30), LocalTime.of(13, 30), LocalTime.of(11, 30), null)),
            0.10);

        assertThat(properties.toSettings().mealWindows())
            .singleElement()
            .satisfies(meal -> {
                assertThat(meal.bandStart()).isEqualTo(LocalTime.of(11, 30));
                assertThat(meal.bandEnd()).isEqualTo(LocalTime.of(13, 30));
            });
    }

    @Test
    @DisplayName("no configured meals falls back to the sanctioned defaults, bands and all")
    void absent_meals_fall_back_to_the_defaults() {
        assertThat(new HumanizationProperties(List.of(), 0.10).toSettings().mealWindows())
            .usingRecursiveComparison()
            .isEqualTo(HumanizationSettings.DEFAULT.mealWindows());
        assertThat(new HumanizationProperties(null, 0.10).toSettings().mealWindows())
            .usingRecursiveComparison()
            .isEqualTo(HumanizationSettings.DEFAULT.mealWindows());
    }

    @Test
    @DisplayName("an impossible band fails where the bean is built, not later in a planning run")
    void an_impossible_band_fails_at_mapping_time() {
        // The body of PlannerConfig#humanizationSettings, isolated: the failure happens while the
        // settings are being mapped, so no planning run can ever observe such a band.
        HumanizationProperties properties = new HumanizationProperties(
            List.of(new HumanizationProperties.Meal("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
                LocalTime.of(11, 30), LocalTime.of(13, 0))),
            0.10);

        assertThatThrownBy(properties::toSettings)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plausible band must enclose the meal window");
    }

    @Test
    @DisplayName("the mapping preserves the configured order of the meals")
    void the_mapping_preserves_the_configured_order() {
        HumanizationProperties properties = new HumanizationProperties(
            List.of(
                new HumanizationProperties.Meal("dinner", LocalTime.of(19, 0), LocalTime.of(20, 0),
                    LocalTime.of(18, 0), LocalTime.of(21, 30)),
                new HumanizationProperties.Meal("breakfast", LocalTime.of(7, 0), LocalTime.of(7, 30),
                    LocalTime.of(5, 30), LocalTime.of(10, 0))),
            0.10);

        // The order decides which meal widens a band that holds two of them, so it is not incidental.
        assertThat(properties.toSettings().mealWindows())
            .extracting(MealWindow::label)
            .containsExactly("dinner", "breakfast");
    }

    private static PlannerConstantsLoader stubLoader() {
        PlannerConstantsLoader loader = mock(PlannerConstantsLoader.class);
        when(loader.resolveConstraints()).thenReturn(PlannerConstraints.DEFAULT);
        when(loader.resolveThresholds()).thenReturn(EnergyThresholds.DEFAULT);
        when(loader.resolveDayTemplate()).thenReturn(DayTemplate.DEFAULT);
        return loader;
    }
}
