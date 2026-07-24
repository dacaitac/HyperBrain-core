package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.planner.domain.model.AppUsageBucket;
import com.hyperbrain.planner.domain.port.out.AppUsageStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.intValue;
import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.optionalInt;
import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.requiredInstant;
import static com.hyperbrain.planner.infrastructure.telemetry.TelemetryPayloads.requiredText;

/**
 * Normalizes {@code DEVICE_ACTIVITY/APP_ACTIVITY} into {@code tel_app_usage} rows (ADR-016). The iOS
 * app aggregates Screen Time on device and sends one bucket per (category, time window) — there is no
 * per-window stream — so each bucket becomes one row. Idempotency is the envelope's {@code dedup_key}
 * (the whole envelope is one unit), so this strategy just inserts.
 *
 * <p><b>Expected payload</b> (tolerant reader):
 * <pre>{@code
 * { "buckets": [
 *     { "bucket_start": "...", "bucket_end": "...", "category": "SOCIAL",
 *       "duration_seconds": N, "pickups": N }, ... ] }
 * }</pre>
 * A missing or non-array {@code buckets} is a contract violation (ERROR); {@code pickups} is optional.
 * An empty array normalizes to zero rows (still NORMALIZED). All buckets are parsed before the single
 * batch insert (parse-then-write), so a malformed bucket fails cleanly without a partial write.
 */
@Component
class AppActivityNormalizationStrategy implements TelemetryNormalizationStrategy {

    static final String PROVIDER = "DEVICE_ACTIVITY";
    static final String EVENT_TYPE = "APP_ACTIVITY";

    private final AppUsageStore appUsageStore;

    AppActivityNormalizationStrategy(AppUsageStore appUsageStore) {
        this.appUsageStore = appUsageStore;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void normalize(TelemetryRecord record) {
        JsonNode buckets = record.payload().get("buckets");
        if (buckets == null || !buckets.isArray()) {
            throw new IllegalArgumentException("APP_ACTIVITY payload requires a 'buckets' array");
        }
        List<AppUsageBucket> parsed = new ArrayList<>(buckets.size());
        for (JsonNode bucket : buckets) {
            parsed.add(new AppUsageBucket(
                requiredInstant(bucket, "bucket_start"),
                requiredInstant(bucket, "bucket_end"),
                requiredText(bucket, "category"),
                intValue(bucket, "duration_seconds"),
                optionalInt(bucket, "pickups")));
        }
        appUsageStore.saveBuckets(record.userId(), parsed, record.contextEventId(), record.collectedAt());
    }
}
