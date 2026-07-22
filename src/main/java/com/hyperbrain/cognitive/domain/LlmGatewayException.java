package com.hyperbrain.cognitive.domain;

/**
 * Signals that a call to the {@code LlmGateway} could not produce a usable completion: a transport
 * failure, a timeout, or an empty/blank response. The proposer catches this and degrades to the
 * deterministic floor (ADR-019) — it is never surfaced to the caller as a hard failure, because a
 * degraded day is always better than none.
 */
public class LlmGatewayException extends RuntimeException {

    public LlmGatewayException(String message) {
        super(message);
    }

    public LlmGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
