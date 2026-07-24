package com.hyperbrain.planner.infrastructure.telemetry;

/**
 * Thrown when a telemetry message cannot be landed raw (ADR-016). Reserved for real failures that a
 * redelivery might fix or that belong in the DLQ — a syntactically broken body that is not persistable
 * as raw JSON. It is <b>not</b> used for an unknown {@code (provider, event_type)} (that lands raw and
 * is marked SKIPPED) nor for a payload the normalizer cannot interpret (that lands raw and is marked
 * ERROR); those never reach the DLQ.
 */
public class TelemetryProcessingException extends RuntimeException {

    public TelemetryProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public TelemetryProcessingException(String message) {
        super(message);
    }
}
