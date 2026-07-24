package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;

/**
 * One edge-aggregated Screen Time / DeviceActivity bucket (ADR-016 {@code DEVICE_ACTIVITY/APP_ACTIVITY}):
 * a category's usage over a time window. The iOS app aggregates on device and sends one bucket per
 * (category, window) — there is no per-window stream — so each bucket maps to one {@code tel_app_usage}
 * row.
 *
 * @param bucketStart      window start; never null
 * @param bucketEnd        window end; never null and after {@code bucketStart}
 * @param category         DeviceActivity ActivityCategory token (open set); never blank
 * @param durationSeconds  foreground seconds in the window; non-negative
 * @param pickups          device pickups in the window, or null when the provider omits them
 */
public record AppUsageBucket(
    OffsetDateTime bucketStart,
    OffsetDateTime bucketEnd,
    String category,
    int durationSeconds,
    Integer pickups
) {

    public AppUsageBucket {
        if (bucketStart == null || bucketEnd == null) {
            throw new IllegalArgumentException("app-usage bucket requires start and end instants");
        }
        if (!bucketEnd.isAfter(bucketStart)) {
            throw new IllegalArgumentException("app-usage bucket end must be after start");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("app-usage bucket requires a category");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("app-usage bucket duration must be non-negative");
        }
        if (pickups != null && pickups < 0) {
            throw new IllegalArgumentException("app-usage bucket pickups must be non-negative");
        }
    }
}
