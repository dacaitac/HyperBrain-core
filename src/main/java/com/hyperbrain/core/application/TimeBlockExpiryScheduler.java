package com.hyperbrain.core.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Schedules the block expiry sweep: open blocks whose {@code end_time} passed settle as
 * {@code DONE} (ADR-040 D4 — a container of time that elapsed, not a broken commitment).
 * Separated from {@link TimeBlockSettlementService} so tests can drive {@code expireDueBlocks()}
 * deterministically — the bean is switched off via {@code app.timeblock.scheduling-enabled=false}
 * in the integration-test profile, mirroring the OutboxScheduler pattern. No replanning happens
 * here: relocating the remainder (TaskOverrunDetectedEvent) is HU-02.
 *
 * <p>This is the {@code TIME_BLOCK} arm of the ADR-040 D4 dispatch table, and it keeps its own
 * minute-cadence trigger instead of joining {@link OverdueSweepService}: a block's window closes
 * intraday, and the planner's occupancy walls and the daily rollup both read settled blocks within
 * the same day. Deferring that to a day-close cron would leave a whole day of blocks open.
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
