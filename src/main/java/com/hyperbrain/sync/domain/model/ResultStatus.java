package com.hyperbrain.sync.domain.model;

/**
 * Outcome reported by SentinelAPI for an applied {@link WriteCommand} (ADR-010).
 */
public enum ResultStatus {
    APPLIED,
    FAILED
}
