package com.hyperbrain.sync.domain.model;

/**
 * Type of external entity carried by a {@link SentinelEvent}. The first four values match the
 * {@code entity_type} field of the SentinelAPI → SQS contract (HU-09); {@code TASK} and
 * {@code CYCLE} are the routing keys of normalized Notion webhook envelopes (HU-14), resolved
 * by the Consumer from the payload's parent data source.
 */
public enum EntityType {
    REMINDER,
    CALENDAR_EVENT,
    REMINDER_LIST,
    CALENDAR,
    TASK,
    CYCLE
}
