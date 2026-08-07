package com.hyperbrain.sync.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Type → destination calendar name for the Apple write-back of SYSTEM-owned calendar events
 * (ADR-009: "configurable por usuario"; core#64). The Core owns which named calendar each
 * event-backed executable type lands on — the block goes to "Trabajo", ACTIVITY/LEARNING_SESSION
 * to "HyperBrain" — instead of leaking the ingestion {@code source_calendar}. SentinelAPI
 * resolve-or-creates the calendar by name.
 *
 * <p>Reminder-backed types (TASK, HABIT, LEAD_MEASURE, BUYING) are absent from the map and keep
 * their existing list behaviour ({@code source_calendar}); a type not present in the map falls
 * back to the current {@code source_calendar} default as well.
 */
@ConfigurationProperties(prefix = "app.sync.apple")
public class AppleCalendarProperties {

    /** Executable type → destination calendar name (empty falls back to {@code source_calendar}). */
    private Map<String, String> calendarNames = new HashMap<>();

    public Map<String, String> getCalendarNames() {
        return calendarNames;
    }

    public void setCalendarNames(Map<String, String> calendarNames) {
        this.calendarNames = calendarNames;
    }
}
