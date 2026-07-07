package com.hyperbrain.sync.domain;

/**
 * Raised when a Notion API call fails in a way the write-back cannot resolve locally
 * (persistent 5xx, exhausted retries, or a non-retryable 4xx). Propagating it out of the
 * outbox drain leaves the event unprocessed, so the write is retried on the next poll (CA-13).
 */
public class NotionApiException extends RuntimeException {

    public NotionApiException(String message) {
        super(message);
    }

    public NotionApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
