package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handles normalized {@link EntityType#TASK} events from the Notion Tasks database (HU-14):
 * resolves the current page state (embedded or fetched), then delegates the upsert/delete
 * decision to {@link NotionTaskSyncService} — the operation is derived from the mapping state
 * and the page lifecycle, never from the webhook type (CA-28).
 *
 * <p>Runs inside the ingestion transaction started by {@code SyncEventIngestionService};
 * throwing rolls back the dedup insert and lets SQS redeliver (CA-22).
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTaskEventHandler implements IEventHandler {

    private final NotionWebhookPageResolver pageResolver;
    private final NotionPageParser pageParser;
    private final NotionTaskSyncService taskSyncService;

    public NotionTaskEventHandler(
        NotionWebhookPageResolver pageResolver,
        NotionPageParser pageParser,
        NotionTaskSyncService taskSyncService
    ) {
        this.pageResolver = pageResolver;
        this.pageParser = pageParser;
        this.taskSyncService = taskSyncService;
    }

    @Override
    public EntityType supportedType() {
        return EntityType.TASK;
    }

    @Override
    public void handle(SentinelEvent event) {
        Optional<JsonNode> pageJson = pageResolver.resolve(event);
        if (pageJson.isEmpty()) {
            taskSyncService.deleteByExternalId(event.entityId());
            return;
        }
        NotionTaskPage page = pageParser.parseTask(pageJson.get());
        taskSyncService.apply(page);
    }
}
