package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 */
@Component
@ConditionalOnProperty(name = "app.sync.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class SqsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumer.class);

    private final ObjectMapper objectMapper;
    private final SyncEventIngestionService ingestionService;

    public SqsConsumer(ObjectMapper objectMapper, SyncEventIngestionService ingestionService) {
        this.objectMapper = objectMapper;
        this.ingestionService = ingestionService;
    }

    @SqsListener("${spring.cloud.aws.sqs.queues.sync-events}")
    public void onMessage(String body) {
        SentinelEvent event = deserialize(body);
        validate(event);
        log.info(
            "sync event received: {} {} entityId={} eventId={}",
            event.entityType(),
            event.operation(),
            event.entityId(),
            event.eventId());
        ingestionService.ingest(event);
    }

    private SentinelEvent deserialize(String body) {
        try {
            return objectMapper.readValue(body, SentinelEventMessage.class).toDomain();
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
