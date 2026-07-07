package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handles normalized {@link EntityType#CYCLE} events from the Notion Cycles database
 * (HU-14, ADR-011: Cycles sync is fully bidirectional). Same structure as the task handler:
 * resolve the current page state, delegate to {@link NotionCycleSyncService} (CA-28).
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionCycleEventHandler implements IEventHandler {

    private final NotionWebhookPageResolver pageResolver;
    private final NotionPageParser pageParser;
    private final NotionCycleSyncService cycleSyncService;

    public NotionCycleEventHandler(
        NotionWebhookPageResolver pageResolver,
        NotionPageParser pageParser,
        NotionCycleSyncService cycleSyncService
    ) {
        this.pageResolver = pageResolver;
        this.pageParser = pageParser;
        this.cycleSyncService = cycleSyncService;
    }

    @Override
    public EntityType supportedType() {
        return EntityType.CYCLE;
    }

    @Override
    public void handle(SentinelEvent event) {
        Optional<JsonNode> pageJson = pageResolver.resolve(event);
        if (pageJson.isEmpty()) {
            cycleSyncService.deleteByExternalId(event.entityId());
            return;
        }
        NotionCyclePage page = pageParser.parseCycle(pageJson.get());
        cycleSyncService.apply(page);
    }
}
