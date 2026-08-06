package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.NotionTimeBlockPage;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handles normalized {@link EntityType#TIME_BLOCK} events from the Notion Time Blocks database
 * (ADR-038): resolves the current page state (embedded or fetched), then delegates the merge
 * decision to {@link NotionTimeBlockSyncService} — the operation is derived from the mapping
 * state and the page lifecycle, never from the webhook type (CA-28 pattern, as
 * {@link NotionTaskEventHandler}).
 *
 * <p>Only reachable when the {@code timeblocks-inbound-enabled} kill-switch is on — the
 * normalizer discards Time Blocks deliveries otherwise. Runs inside the ingestion transaction;
 * throwing rolls back the dedup insert and lets SQS redeliver (CA-22).
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTimeBlockEventHandler implements IEventHandler {

    private final NotionWebhookPageResolver pageResolver;
    private final NotionPageParser pageParser;
    private final NotionTimeBlockSyncService timeBlockSyncService;

    public NotionTimeBlockEventHandler(
        NotionWebhookPageResolver pageResolver,
        NotionPageParser pageParser,
        NotionTimeBlockSyncService timeBlockSyncService
    ) {
        this.pageResolver = pageResolver;
        this.pageParser = pageParser;
        this.timeBlockSyncService = timeBlockSyncService;
    }

    @Override
    public EntityType supportedType() {
        return EntityType.TIME_BLOCK;
    }

    @Override
    public void handle(SentinelEvent event) {
        Optional<JsonNode> pageJson = pageResolver.resolve(event);
        if (pageJson.isEmpty()) {
            timeBlockSyncService.handleArchived(event.entityId());
            return;
        }
        NotionTimeBlockPage page = pageParser.parseTimeBlock(pageJson.get());
        timeBlockSyncService.apply(page);
    }
}
