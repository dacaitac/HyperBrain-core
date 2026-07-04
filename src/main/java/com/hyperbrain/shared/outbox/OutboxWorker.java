package com.hyperbrain.shared.outbox;

import com.hyperbrain.shared.messaging.IEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional Outbox relay: drains {@code outbox_events} and hands each event to the
 * {@link IEventPublisher}. The single component that reads the outbox table.
 *
 * <p>Draining runs in one transaction so the {@code FOR UPDATE SKIP LOCKED} claim holds until
 * commit — concurrent workers never publish the same event. A publish failure is logged and
 * skipped (the row stays {@code processed = false}) so it is retried on the next poll; it does
 * not roll back siblings already published in the same batch.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository repository;
    private final IEventPublisher publisher;
    private final OutboxProperties properties;

    public OutboxWorker(OutboxRepository repository, IEventPublisher publisher, OutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * Claims and publishes one batch of unprocessed events.
     *
     * @return the number of events successfully published in this batch
     */
    @Transactional
    public int drainBatch() {
        List<OutboxEvent> batch = repository.lockUnprocessedBatch(properties.getBatchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                repository.markProcessed(event.id());
                published++;
            } catch (RuntimeException ex) {
                log.error("Failed to publish outbox event {}; leaving unprocessed for retry", event.id(), ex);
            }
        }
        if (published > 0) {
            log.debug("Outbox drain published {}/{} events", published, batch.size());
        }
        return published;
    }

    /**
     * Deletes processed events older than the configured retention window.
     *
     * @return the number of rows purged
     */
    @Transactional
    public int purgeExpired() {
        int deleted = repository.purgeProcessedOlderThan(properties.getRetentionDays());
        if (deleted > 0) {
            log.info("Purged {} processed outbox events older than {} days", deleted, properties.getRetentionDays());
        }
        return deleted;
    }
}
