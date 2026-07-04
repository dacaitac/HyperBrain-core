package com.hyperbrain.shared.messaging.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.shared.messaging.EventPublishingException;
import com.hyperbrain.shared.messaging.IEventPublisher;
import com.hyperbrain.shared.outbox.OutboxEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * SQS adapter for {@link IEventPublisher}: the only component coupled to the messaging transport.
 * Routes each event to an outbound queue by aggregate type — cognitive work to {@code ia-jobs},
 * everything else to {@code core-events}. It never publishes to {@code sync-events.fifo}, which
 * is inbound only (loop protection).
 */
@Component
public class SqsEventPublisher implements IEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsEventPublisher.class);

    /**
     * Aggregate types whose events are asynchronous IA jobs. Extended as the cognitive module
     * lands; until then everything defaults to {@code core-events}.
     */
    private static final Set<String> COGNITIVE_AGGREGATES = Set.of("IA_JOB");

    private final SqsTemplate sqsTemplate;
    private final ObjectMapper objectMapper;
    private final String coreEventsQueue;
    private final String iaJobsQueue;

    public SqsEventPublisher(
        SqsTemplate sqsTemplate,
        ObjectMapper objectMapper,
        @Value("${spring.cloud.aws.sqs.queues.core-events}") String coreEventsQueue,
        @Value("${spring.cloud.aws.sqs.queues.ia-jobs}") String iaJobsQueue
    ) {
        this.sqsTemplate = sqsTemplate;
        this.objectMapper = objectMapper;
        this.coreEventsQueue = coreEventsQueue;
        this.iaJobsQueue = iaJobsQueue;
    }

    @Override
    public void publish(OutboxEvent event) {
        String queue = resolveQueue(event.aggregateType());
        String body = toEnvelope(event);
        sqsTemplate.send(queue, body);
        log.debug("Published event {} ({}) to {}", event.id(), event.eventType(), queue);
    }

    private String resolveQueue(String aggregateType) {
        return COGNITIVE_AGGREGATES.contains(aggregateType) ? iaJobsQueue : coreEventsQueue;
    }

    private String toEnvelope(OutboxEvent event) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("event_type", event.eventType());
            envelope.put("aggregate_type", event.aggregateType());
            envelope.put("aggregate_id", event.aggregateId());
            envelope.set("payload", objectMapper.readTree(event.payload()));
            if (event.sourceSystem() != null) {
                envelope.put("source_system", event.sourceSystem());
            }
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new EventPublishingException("Failed to serialize outbox event " + event.id(), ex);
        }
    }
}
