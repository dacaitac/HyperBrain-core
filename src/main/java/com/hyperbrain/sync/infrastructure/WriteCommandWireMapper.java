package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.sync.domain.EventProcessingException;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.model.WritePayload;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Serializes {@link WriteCommand}s into the SentinelAPI wire contract (TD-03, AsyncAPI v1.2.0):
 * {@code snake_case} keys and ISO-8601 timestamps with offset, truncated to whole seconds —
 * SentinelAPI's {@code ISO8601DateFormatter} rejects fractional seconds. All contract-required
 * keys are always present ({@code alarms} as an empty array) because the Swift decoder treats
 * them as non-optional.
 */
@Component
public class WriteCommandWireMapper {

    private static final DateTimeFormatter WIRE_DATE = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectMapper objectMapper;

    public WriteCommandWireMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the full wire JSON for a command ({@code command_id}, {@code command_type},
     * {@code operation}, {@code entity_id}, {@code payload}).
     *
     * @param command the command to serialize
     * @return wire JSON string
     */
    public String toWireJson(WriteCommand command) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("command_id", command.commandId().toString());
        root.put("command_type", command.commandType().name());
        root.put("operation", command.operation().name());
        if (command.entityId() != null) {
            root.put("entity_id", command.entityId());
        } else {
            root.putNull("entity_id");
        }
        if (command.payload() != null) {
            root.set("payload", payloadNode(command.payload()));
        } else {
            root.putNull("payload");
        }
        return write(root);
    }

    /**
     * Returns the wire JSON of the payload alone — persisted in {@code sync_write_commands}
     * to replay the checksum when the {@code WriteCommandResult} arrives (ADR-010).
     *
     * @param payload the payload to serialize
     * @return wire JSON string
     */
    public String payloadJson(WritePayload payload) {
        return write(payloadNode(payload));
    }

    /**
     * Extracts the Apple command type hinted in an outbox event payload ({@code "type"} field
     * carrying the {@code core_executable.type}); used on DELETE, when the executable row no
     * longer exists.
     *
     * @param outboxPayloadJson raw outbox event payload (may be null/blank)
     * @return the command type, or empty when absent or not writable
     */
    public Optional<CommandType> extractCommandType(String outboxPayloadJson) {
        if (outboxPayloadJson == null || outboxPayloadJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode type = objectMapper.readTree(outboxPayloadJson).path("type");
            if (!type.isTextual()) {
                return Optional.empty();
            }
            return switch (type.asText()) {
                case "TASK" -> Optional.of(CommandType.REMINDER);
                case "ACTIVITY" -> Optional.of(CommandType.CALENDAR_EVENT);
                default -> Optional.empty();
            };
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    private ObjectNode payloadNode(WritePayload payload) {
        return switch (payload) {
            case ReminderPayload reminder -> reminderNode(reminder);
            case CalendarEventPayload event -> calendarEventNode(event);
        };
    }

    private ObjectNode reminderNode(ReminderPayload payload) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", payload.title());
        putNullable(node, "notes", payload.notes());
        putNullableDate(node, "due_date", payload.dueDate());
        node.put("completed", payload.completed());
        node.put("priority", payload.priority());
        node.putNull("url");
        node.putNull("recurrence");
        node.put("list_id", payload.listId());
        node.put("list_name", payload.listName());
        node.putNull("location");
        node.putArray("alarms");
        return node;
    }

    private ObjectNode calendarEventNode(CalendarEventPayload payload) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", payload.title());
        node.put("start_time", wireDate(payload.startTime()));
        putNullableDate(node, "end_time", payload.endTime());
        node.put("all_day", payload.allDay());
        putNullable(node, "notes", payload.notes());
        node.putNull("url");
        node.putNull("recurrence");
        node.put("calendar_id", payload.calendarId());
        node.put("calendar_name", payload.calendarName());
        putNullable(node, "location", payload.location());
        node.putArray("alarms");
        return node;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        } else {
            node.putNull(field);
        }
    }

    private static void putNullableDate(ObjectNode node, String field, OffsetDateTime value) {
        if (value != null) {
            node.put(field, wireDate(value));
        } else {
            node.putNull(field);
        }
    }

    private static String wireDate(OffsetDateTime value) {
        return value.truncatedTo(ChronoUnit.SECONDS).format(WIRE_DATE);
    }

    private String write(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Failed to serialize WriteCommand payload", ex);
        }
    }
}
