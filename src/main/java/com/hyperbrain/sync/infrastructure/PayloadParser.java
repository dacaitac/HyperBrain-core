package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.EventProcessingException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Deserializes raw JSON payloads from the SentinelAPI → SQS contract into domain records.
 * The wire format uses {@code snake_case} keys and ISO-8601 timestamps with timezone offsets
 * (matching SentinelAPI's {@code JSONCoding.makeEncoder()}).
 */
@Component
public class PayloadParser {

    private final ObjectMapper objectMapper;

    public PayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a REMINDER payload JSON string into a {@link ReminderPayload} domain record.
     *
     * @param json raw JSON string (may be null)
     * @return the parsed payload
     * @throws EventProcessingException if the JSON is malformed or missing required fields
     */
    public ReminderPayload parseReminder(String json) {
        if (json == null || json.isBlank()) {
            throw new EventProcessingException("REMINDER payload is required for CREATED/UPDATED");
        }
        try {
            ReminderJson wire = objectMapper.readValue(json, ReminderJson.class);
            return new ReminderPayload(
                wire.title,
                wire.notes,
                wire.dueDate,
                wire.completed,
                wire.priority,
                wire.listId,
                wire.listName);
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Failed to parse REMINDER payload: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses a CALENDAR_EVENT payload JSON string into a {@link CalendarEventPayload} domain record.
     *
     * @param json raw JSON string (may be null)
     * @return the parsed payload
     * @throws EventProcessingException if the JSON is malformed or missing required fields
     */
    public CalendarEventPayload parseCalendarEvent(String json) {
        if (json == null || json.isBlank()) {
            throw new EventProcessingException("CALENDAR_EVENT payload is required for CREATED/UPDATED");
        }
        try {
            CalendarEventJson wire = objectMapper.readValue(json, CalendarEventJson.class);
            return new CalendarEventPayload(
                wire.title,
                wire.startTime,
                wire.endTime,
                wire.allDay,
                wire.notes,
                wire.calendarId,
                wire.calendarName,
                wire.location);
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Failed to parse CALENDAR_EVENT payload: " + ex.getMessage(), ex);
        }
    }

    // ── Wire DTOs (snake_case; unknown fields ignored for forward-compatibility) ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ReminderJson {
        public String title;
        public String notes;
        @JsonProperty("due_date") public OffsetDateTime dueDate;
        public boolean completed;
        public int priority;
        @JsonProperty("list_id")   public String listId;
        @JsonProperty("list_name") public String listName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CalendarEventJson {
        public String title;
        @JsonProperty("start_time")    public OffsetDateTime startTime;
        @JsonProperty("end_time")      public OffsetDateTime endTime;
        @JsonProperty("all_day")       public boolean allDay;
        public String notes;
        @JsonProperty("calendar_id")   public String calendarId;
        @JsonProperty("calendar_name") public String calendarName;
        public String location;
    }
}
