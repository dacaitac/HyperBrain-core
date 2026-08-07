package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.List;

/**
 * What is left of the floor's calibration once ADR-040 D1 has retired the capacity discounts, bound
 * from {@code app.planner.humanize.*}. Kept out of the domain services so the meal anchors and the
 * batching band are configuration rather than hard-coded formula constants.
 *
 * <p>The buffer, the minimum block and the occupancy band are gone with the mechanisms they calibrated.
 * Their keys are simply ignored if they linger in a deployed {@code application.yml}, which is what
 * makes this a deploy-safe removal rather than a boot failure.
 *
 * @param meals          the protected meal anchors, local wall-clock windows
 * @param batchBandWidth the priority-score band within which context batching applies
 */
@ConfigurationProperties(prefix = "app.planner.humanize")
public record HumanizationProperties(List<Meal> meals, double batchBandWidth) {

    /**
     * One configured meal anchor.
     *
     * @param label the meal name (e.g. "lunch")
     * @param start the local start time (e.g. {@code 12:30})
     * @param end   the local end time (e.g. {@code 13:30})
     */
    public record Meal(String label, LocalTime start, LocalTime end) {
    }

    /**
     * Maps the bound properties onto the domain settings, falling back to the sanctioned defaults when a
     * section is absent so a partial {@code application.yml} still yields a valid floor.
     *
     * @return the domain settings
     */
    public HumanizationSettings toSettings() {
        List<MealWindow> mealWindows = (meals == null || meals.isEmpty())
            ? HumanizationSettings.DEFAULT.mealWindows()
            : meals.stream().map(m -> new MealWindow(m.label(), m.start(), m.end())).toList();
        double band = batchBandWidth > 0 ? batchBandWidth : HumanizationSettings.DEFAULT.batchBandWidth();
        return new HumanizationSettings(mealWindows, band);
    }
}
