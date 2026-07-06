package com.hyperbrain.sync.domain.model;

/**
 * Discriminates the payload shape of a {@link WriteCommand} (TD-03). Only reminders and
 * calendar events are writable from the Core; lists and calendars are read-only.
 */
public enum CommandType {
    REMINDER,
    CALENDAR_EVENT
}
