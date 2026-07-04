package com.hyperbrain.shared.messaging;

import com.hyperbrain.shared.outbox.OutboxEvent;

/**
 * Outbound port: publishes a drained {@link OutboxEvent} to the messaging transport.
 * The only implementation ({@code SqsEventPublisher}) is the sole component coupled to SQS.
 */
public interface IEventPublisher {

    /**
     * Publishes an event to its destination queue (resolved from the aggregate type).
     *
     * @param event the outbox event to publish
     * @throws EventPublishingException if serialization or transport fails
     */
    void publish(OutboxEvent event);
}
