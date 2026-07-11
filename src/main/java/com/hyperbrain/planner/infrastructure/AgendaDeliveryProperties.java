package com.hyperbrain.planner.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calibrable constants of the morning agenda dispatch (HU-01b delivery slice), bound from
 * {@code app.planner.delivery.*}. Kept out of the domain services so the lead offset and hysteresis
 * margin are configuration, not hard-coded formula constants.
 *
 * @param leadOffsetMinutes       minutes after the estimated wake edge at which the dispatch fires
 *                                (Daniel: +10)
 * @param hysteresisMarginMinutes the maximum day-to-day move of the trigger vs. yesterday's
 *                                (Daniel: ±15) — absorbs the ~±40 min error of the 5-night wake median
 * @param triggerToleranceMinutes how far past the trigger minute a scheduler tick may still fire,
 *                                matching the scheduler cadence so no window is missed between ticks
 */
@ConfigurationProperties(prefix = "app.planner.delivery")
public record AgendaDeliveryProperties(
    int leadOffsetMinutes,
    int hysteresisMarginMinutes,
    int triggerToleranceMinutes
) {

    public AgendaDeliveryProperties {
        if (leadOffsetMinutes < 0) {
            throw new IllegalArgumentException("leadOffsetMinutes must be non-negative: " + leadOffsetMinutes);
        }
        if (hysteresisMarginMinutes < 0) {
            throw new IllegalArgumentException(
                "hysteresisMarginMinutes must be non-negative: " + hysteresisMarginMinutes);
        }
        if (triggerToleranceMinutes < 1) {
            throw new IllegalArgumentException(
                "triggerToleranceMinutes must be positive: " + triggerToleranceMinutes);
        }
    }
}
