package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.application.SyncEventIngestionService;
import com.hyperbrain.sync.domain.EventProcessingException;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SQS adapter for the inbound sync pipeline. Listens on {@code sync-events.fifo}, deserializes and
 * validates the message, then delegates to {@link SyncEventIngestionService}. It holds no business
 * logic: dedup, loop protection and routing live in the application layer.
 *
 * <p>On a malformed or schema-invalid message it throws {@link EventProcessingException}, so Spring
 * Cloud AWS does not delete the message — it is redelivered and eventually sent to the DLQ.
 *
 * <p>Gated by {@code app.sync.consumer.enabled} (default on). Integration tests that don't exercise
 * inbound consumption switch it off so that only one listener ever competes for the shared queue.
 *
 * <p>Notion webhook deliveries (ADR-011) share this queue but carry a lighter envelope
 * {@code {source_system, message_id, timestamp, payload}}: they are acknowledged with dedup and no
 * routing until the Notion ingestion lands (HU-14), so they never poison the DLQ.
 */
@Component
@ConditionalOnProperty(name = "app.sync.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class SqsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumer.class);

    private static final String NOTION_SOURCE_SYSTEM = "NOTION";

    private final ObjectMapper objectMapper;
    private final SyncEventIngestionService ingestionService;

    public SqsConsumer(ObjectMapper objectMapper, SyncEventIngestionService ingestionService) {
        this.objectMapper = objectMapper;
        this.ingestionService = ingestionService;
    }

    @SqsListener("${spring.cloud.aws.sqs.queues.sync-events}")
    public void onMessage(String body) {
        JsonNode root = parse(body);
        if (NOTION_SOURCE_SYSTEM.equals(root.path("source_system").asText())) {
            ingestionService.acknowledgeNotionEvent(notionMessageId(root));
            return;
        }
        SentinelEvent event = deserialize(root);
        validate(event);
        log.info(
            "sync event received: {} {} entityId={} eventId={}",
            event.entityType(),
            event.operation(),
            event.entityId(),
            event.eventId());
        ingestionService.ingest(event);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Malformed sync event payload", ex);
        }
    }

    private static String notionMessageId(JsonNode root) {
        String messageId = root.path("message_id").asText(null);
        if (messageId == null || messageId.isBlank()) {
            throw new EventProcessingException("Notion sync event missing message_id");
        }
        return messageId;
    }

    private SentinelEvent deserialize(JsonNode root) {
        try {
            return objectMapper.treeToValue(root, SentinelEventMessage.class).toDomain();
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Malformed sync event payload", ex);
        }
    }

    private void validate(SentinelEvent event) {
        if (event.eventId() == null
            || event.sourceSystem() == null
            || event.entityType() == null
            || event.operation() == null
            || event.entityId() == null) {
            throw new EventProcessingException("Sync event missing required fields: " + event);
        }
        log.debug("Accepted sync event {} ({}/{})", event.eventId(), event.entityType(), event.operation());
    }
}
