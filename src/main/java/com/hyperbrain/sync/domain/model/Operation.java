package com.hyperbrain.sync.domain.model;

/**
 * Lifecycle operation an external system performed on an entity.
 * Matches the {@code operation} field of the SentinelAPI → SQS contract (HU-09).
 */
public enum Operation {
    CREATED,
    UPDATED,
    DELETED
}
