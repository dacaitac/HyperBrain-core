package com.hyperbrain.sync.domain.model;

/**
 * Type of external entity carried by a {@link SentinelEvent}.
 * Matches the {@code entity_type} field of the SentinelAPI → SQS contract (HU-09).
 */
public enum EntityType {
    REMINDER,
    CALENDAR_EVENT,
    REMINDER_LIST,
    CALENDAR
}
