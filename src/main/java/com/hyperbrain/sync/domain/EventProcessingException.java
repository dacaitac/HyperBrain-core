package com.hyperbrain.sync.domain;

/**
 * Thrown when an inbound sync event cannot be processed (malformed payload, invalid schema,
 * or a failing handler). Propagated out of the SQS listener so Spring Cloud AWS does not delete
 * the message: it is redelivered and, after {@code maxReceiveCount}, routed to the DLQ.
 */
public class EventProcessingException extends RuntimeException {

    public EventProcessingException(String message) {
        super(message);
    }

    public EventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
