package com.hyperbrain.planner.infrastructure.telemetry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the raw telemetry retention sweep (ADR-016). Separated from
 * {@link TelemetryRetentionService} so integration tests can drive {@code purgeExpired()} directly;
 * the bean is switched off via {@code app.telemetry.retention.scheduling-enabled=false} in the
 * integration-test profile. Mirrors {@code OutboxScheduler}.
 */
@Component
@ConditionalOnProperty(prefix = "app.telemetry.retention", name = "scheduling-enabled",
    havingValue = "true", matchIfMissing = true)
class TelemetryRetentionScheduler {

    private final TelemetryRetentionService retentionService;

    TelemetryRetentionScheduler(TelemetryRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${app.telemetry.retention.cron:0 45 3 * * *}")
    public void purge() {
        retentionService.purgeExpired();
    }
}
