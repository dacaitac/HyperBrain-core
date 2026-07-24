package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.AppUsageBucket;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Out-port for persisting edge-aggregated Screen Time / DeviceActivity buckets (ADR-016
 * {@code DEVICE_ACTIVITY/APP_ACTIVITY}) into {@code tel_app_usage}.
 *
 * <p>Idempotency is handled upstream at the envelope level (semantic {@code dedup_key} on
 * {@code context_event}): the normalizer runs at most once per envelope, so this port performs a
 * plain insert per bucket without its own dedup.
 */
public interface AppUsageStore {

    /**
     * Inserts one {@code tel_app_usage} row per bucket, each linked back to the raw envelope.
     *
     * @param userId         the owning user; never null
     * @param buckets        the buckets to persist; never null (may be empty — then a no-op)
     * @param contextEventId the raw {@code context_event} row the buckets were normalized from; never null
     * @param collectedAt    when the collector captured the buckets; never null
     */
    void saveBuckets(UUID userId, List<AppUsageBucket> buckets, UUID contextEventId, OffsetDateTime collectedAt);
}
