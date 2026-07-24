package com.hyperbrain.planner.infrastructure.telemetry;

import com.hyperbrain.planner.domain.port.out.RawTelemetryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention sweep for the raw-first landing zone (ADR-016): deletes NORMALIZED and SKIPPED
 * {@code context_event} rows past the retention window, keeping ERROR rows for diagnosis. Mirrors the
 * outbox retention split (service does the work, {@link TelemetryRetentionScheduler} triggers it) so
 * tests can drive {@link #purgeExpired()} deterministically.
 *
 * <p>{@code app.telemetry.retention-days} (default 90) is a sanctioned MVP default, calibrable by Daniel.
 */
@Service
public class TelemetryRetentionService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRetentionService.class);

    private final RawTelemetryStore rawTelemetryStore;
    private final int retentionDays;

    TelemetryRetentionService(
        RawTelemetryStore rawTelemetryStore,
        @Value("${app.telemetry.retention-days:90}") int retentionDays
    ) {
        this.rawTelemetryStore = rawTelemetryStore;
        this.retentionDays = retentionDays;
    }

    /**
     * Deletes NORMALIZED/SKIPPED raw telemetry rows older than the retention window.
     *
     * @return the number of rows purged
     */
    @Transactional
    public int purgeExpired() {
        int deleted = rawTelemetryStore.purgeProcessedOlderThan(retentionDays);
        if (deleted > 0) {
            log.info("Purged {} normalized/skipped raw telemetry rows older than {} days",
                deleted, retentionDays);
        }
        return deleted;
    }
}
