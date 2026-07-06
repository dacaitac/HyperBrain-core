package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;

/**
 * Domain representation of the CALENDAR_EVENT payload from the SentinelAPI → SQS contract (TD-03).
 *
 * @param title        event title; always present
 * @param startTime    start time with timezone offset; always present
 * @param endTime      end time; null for open-ended events
 * @param allDay       whether the event spans a full day
 * @param notes        optional notes
 * @param calendarId   EKCalendar identifier of the containing calendar
 * @param calendarName display name of the calendar; written to {@code source_calendar}
 * @param location     optional location string
 */
public record CalendarEventPayload(
    String title,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    boolean allDay,
    String notes,
    String calendarId,
    String calendarName,
    String location
) implements WritePayload {
}
