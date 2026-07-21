package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.List;

/**
 * The calibrable constants of the humanized deterministic floor (H1, HU-01c), bound from
 * {@code app.planner.humanize.*}. Kept out of the domain services so the buffers, meal anchors,
 * minimum block, batching band and occupancy band are configuration — not hard-coded formula
 * constants. Maps to the domain {@link HumanizationSettings}; every value is a sanctioned MVP default
 * pending Daniel's ratification.
 *
 * @param transitionBufferMinutes spacing after each execution block (rule 1)
 * @param meals                   the protected meal anchors, local wall-clock windows (rule 2)
 * @param minBlockMinutes         the minimum viable block duration (rule 3)
 * @param batchBandWidth          the priority-score band within which context batching applies (rule 4)
 * @param occupancyMinFraction    the aspirational lower edge of the occupancy band (rule 6)
 * @param occupancyMaxFraction    the hard upper cap of the occupancy band (rule 6)
 */
@ConfigurationProperties(prefix = "app.planner.humanize")
public record HumanizationProperties(
    int transitionBufferMinutes,
    List<Meal> meals,
    int minBlockMinutes,
    double batchBandWidth,
    double occupancyMinFraction,
    double occupancyMaxFraction
) {

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
     * section is absent so a partial {@code application.yml} still yields a valid, humanized floor.
     *
     * @return the domain humanization settings
     */
    public HumanizationSettings toSettings() {
        List<MealWindow> mealWindows = (meals == null || meals.isEmpty())
            ? HumanizationSettings.DEFAULT.mealWindows()
            : meals.stream().map(m -> new MealWindow(m.label(), m.start(), m.end())).toList();
        return new HumanizationSettings(
            transitionBufferMinutes,
            mealWindows,
            minBlockMinutes,
            batchBandWidth,
            occupancyMinFraction,
            occupancyMaxFraction);
    }
}
