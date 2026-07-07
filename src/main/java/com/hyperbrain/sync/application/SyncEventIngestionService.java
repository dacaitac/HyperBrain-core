package com.hyperbrain.sync.application;

import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates inbound event ingestion: loop protection, deduplication and routing, all in one
 * transaction. Because the dedup insert and the handler run together, a failing handler rolls the
 * insert back and lets SQS redeliver — the message is never silently lost.
 */
@Service
public class SyncEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SyncEventIngestionService.class);

    /**
     * Identity the Core stamps on its own outbound events. An inbound event bearing it looped back
     * from our own propagation and must be dropped before any processing (RF-17).
     */
    private static final String SELF_SOURCE_SYSTEM = "HYPERBRAIN_CORE";

    /** Diagnostic classification stored in {@code processed_message} for Notion webhook envelopes. */
    private static final String NOTION_EVENT_TYPE = "NOTION_WEBHOOK";

    private final ProcessedMessageStore processedMessageStore;
    private final EventRouter eventRouter;

    public SyncEventIngestionService(ProcessedMessageStore processedMessageStore, EventRouter eventRouter) {
        this.processedMessageStore = processedMessageStore;
        this.eventRouter = eventRouter;
    }

    /**
     * Ingests one deserialized event: drops self-originated loops, deduplicates by {@code eventId},
     * then routes to the matching handler.
     *
     * @param event the event to ingest
     */
    @Transactional
    public void ingest(SentinelEvent event) {
        if (SELF_SOURCE_SYSTEM.equals(event.sourceSystem())) {
            log.debug("Loop protection: dropping self-originated event {}", event.eventId());
            return;
        }

        boolean firstTime = processedMessageStore.markProcessed(event.eventId(), event.entityType().name());
        if (!firstTime) {
            log.warn("Duplicate event {} ignored (already processed)", event.eventId());
            return;
        }

        eventRouter.route(event);
    }

    /**
     * Acknowledges a Notion webhook envelope (ADR-011): deduplicates by {@code message_id} and
     * stops there — no routing exists until the Notion ingestion lands (HU-14). Acknowledging
     * instead of failing keeps prod deliveries from cycling into the DLQ meanwhile.
     *
     * @param messageId stable webhook delivery identity used for deduplication
     */
    @Transactional
    public void acknowledgeNotionEvent(String messageId) {
        boolean firstTime = processedMessageStore.markProcessed(messageId, NOTION_EVENT_TYPE);
        if (!firstTime) {
            log.warn("Duplicate event {} ignored (already processed)", messageId);
            return;
        }
        log.info("notion event received: message_id={} acknowledged without routing (HU-14 pending)", messageId);
    }
}
