package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;

/**
 * Domain representation of the REMINDER payload from the SentinelAPI → SQS contract (TD-03).
 * Fields mirror the snake_case JSON keys produced by SentinelAPI; optional fields are nullable.
 *
 * @param title     reminder title; always present
 * @param notes     optional notes
 * @param dueDate   optional due date with timezone offset
 * @param completed whether the reminder is marked done
 * @param priority  EventKit priority (0–9)
 * @param listId    EKCalendar identifier of the containing list
 * @param listName  display name of the containing list; written to {@code source_calendar}
 */
public record ReminderPayload(
    String title,
    String notes,
    OffsetDateTime dueDate,
    boolean completed,
    int priority,
    String listId,
    String listName
) implements WritePayload {
}
