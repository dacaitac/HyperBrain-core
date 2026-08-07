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
     * One configured meal anchor and the plausible band it may float within.
     *
     * @param label     the meal name (e.g. "lunch")
     * @param start     the local start time (e.g. {@code 12:30})
     * @param end       the local end time (e.g. {@code 13:30})
     * @param bandStart the earliest plausible hour for this meal (e.g. {@code 11:30}); absent means the
     *                  meal may not float earlier than its own window
     * @param bandEnd   the latest plausible hour for this meal (e.g. {@code 14:30}); absent means the
     *                  meal may not float later than its own window
     */
    public record Meal(String label, LocalTime start, LocalTime end,
                       LocalTime bandStart, LocalTime bandEnd) {

        /** The band as the domain needs it: the configured hour, or the anchor's own edge when absent. */
        MealWindow toWindow() {
            return new MealWindow(label, start, end,
                bandStart == null ? start : bandStart,
                bandEnd == null ? end : bandEnd);
        }
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
            : meals.stream().map(Meal::toWindow).toList();
        double band = batchBandWidth > 0 ? batchBandWidth : HumanizationSettings.DEFAULT.batchBandWidth();
        return new HumanizationSettings(mealWindows, band);
    }
}
