package com.hyperbrain.shared.messaging;

/**
 * Thrown when an {@link IEventPublisher} cannot serialize or transmit an event. Caught by the
 * {@code OutboxWorker}, which leaves the row unprocessed so it is retried on the next poll.
 */
public class EventPublishingException extends RuntimeException {

    public EventPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}
