package com.hyperbrain.core.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Schedules the DR-08 expiry sweep: open blocks whose {@code date_end} passed settle as
 * EXPIRED. Separated from {@link TimeBlockSettlementService} so tests can drive
 * {@code expireDueBlocks()} deterministically — the bean is switched off via
 * {@code app.timeblock.scheduling-enabled=false} in the integration-test profile, mirroring
 * the OutboxScheduler pattern. No replanning happens here: relocating the remainder
 * (TaskOverrunDetectedEvent) is HU-02.
 */
@Component
@ConditionalOnProperty(prefix = "app.timeblock", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TimeBlockExpiryScheduler {

    private final TimeBlockSettlementService settlementService;

    public TimeBlockExpiryScheduler(TimeBlockSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(fixedDelayString = "${app.timeblock.expiry-poll-ms:60000}")
    public void expire() {
        settlementService.expireDueBlocks(OffsetDateTime.now());
    }
}
