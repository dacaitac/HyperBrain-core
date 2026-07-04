package com.hyperbrain.shared.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules the {@link OutboxWorker} triggers. Separated from the worker so that tests can drive
 * {@code drainBatch()} deterministically: the bean is switched off via
 * {@code app.outbox.scheduling-enabled=false} in the integration-test profile, and the poll
 * interval is intentionally kept simple ({@code @Scheduled}, no async executor) for the MVP.
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxWorker worker;

    public OutboxScheduler(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void poll() {
        worker.drainBatch();
    }

    @Scheduled(cron = "${app.outbox.purge-cron:0 0 3 * * *}")
    public void purge() {
        worker.purgeExpired();
    }
}
