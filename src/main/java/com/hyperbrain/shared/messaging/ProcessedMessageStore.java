package com.hyperbrain.shared.messaging;

/**
 * Consumer-side idempotency store ({@code processed_message}). Guards against SQS at-least-once
 * redelivery: an event is acted upon only if its id has not been seen before.
 */
public interface ProcessedMessageStore {

    /**
     * Records that a message has been processed, atomically and idempotently
     * ({@code INSERT ... ON CONFLICT DO NOTHING}).
     *
     * @param messageId stable message identity (the contract's {@code event_id})
     * @param eventType optional classification stored for diagnostics
     * @return {@code true} if this call inserted the row (first time seen);
     *         {@code false} if it already existed (duplicate — caller must skip processing)
     */
    boolean markProcessed(String messageId, String eventType);
}
