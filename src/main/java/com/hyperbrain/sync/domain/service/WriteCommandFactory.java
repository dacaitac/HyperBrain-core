package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.model.WritePayload;

import java.util.Optional;
import java.util.UUID;

/**
 * Builds outbound {@link WriteCommand}s from a {@link CoreExecutable} — the inverse of the
 * HU-09 inbound mapping (CA-5, HU-09c). Pure domain logic; the caller supplies identifiers
 * and decides the effective operation.
 *
 * <p>Type mapping: {@code TASK} → {@code REMINDER}; {@code ACTIVITY} → {@code CALENDAR_EVENT}.
 * {@code AGENDA} executables are read-only by contract (ADR-009) and never produce a command;
 * any other type has no Apple counterpart and is skipped.
 *
 * <p>The Core does not persist EventKit list/calendar identifiers, so {@code list_id} /
 * {@code calendar_id} travel empty and {@code list_name} / {@code calendar_name} carry
 * {@code source_calendar}; SentinelAPI resolves the target list by name or falls back to
 * the default one.
 */
public final class WriteCommandFactory {

    private static final String TYPE_TASK = "TASK";
    private static final String TYPE_ACTIVITY = "ACTIVITY";
    private static final String TYPE_AGENDA = "AGENDA";
    private static final String STATUS_DONE = "DONE";

    private WriteCommandFactory() {}

    /**
     * Builds a CREATED/UPDATED command for an executable, or empty when the executable is not
     * writable to Apple (AGENDA per ADR-009, unsupported type, or a calendar event without
     * {@code startTime} — required by the EventKit contract).
     *
     * @param commandId  correlation id for the command
     * @param executable current state of the {@code core_executable} row
     * @param operation  {@code CREATED} or {@code UPDATED}
     * @param entityId   EventKit identifier for UPDATED; {@code null} for CREATED
     * @return the command, or empty if the executable must not be written to Apple
     */
    public static Optional<WriteCommand> forUpsert(
        UUID commandId, CoreExecutable executable, Operation operation, String entityId) {
        return payloadFor(executable).map(payload -> new WriteCommand(
            commandId, commandTypeOf(payload), operation, entityId, payload));
    }

    /**
     * Builds a DELETED command targeting an already-mapped EventKit entity.
     *
     * @param commandId   correlation id for the command
     * @param commandType entity kind to delete on the Apple side
     * @param entityId    EventKit identifier from the {@code sync_mapping}
     * @return the command (never empty; deletion needs no payload)
     */
    public static WriteCommand forDelete(UUID commandId, CommandType commandType, String entityId) {
        return new WriteCommand(commandId, commandType, Operation.DELETED, entityId, null);
    }

    /**
     * Returns whether an executable type is eligible for write-back at all. {@code AGENDA} is
     * explicitly blocked (ADR-009); types without an Apple counterpart are simply not writable.
     *
     * @param executableType the {@code core_executable.type} value
     * @return true only for {@code TASK} and {@code ACTIVITY}
     */
    public static boolean isWritable(String executableType) {
        return TYPE_TASK.equals(executableType) || TYPE_ACTIVITY.equals(executableType);
    }

    private static Optional<WritePayload> payloadFor(CoreExecutable executable) {
        if (TYPE_TASK.equals(executable.type())) {
            return Optional.of(reminderPayload(executable));
        }
        if (TYPE_ACTIVITY.equals(executable.type())) {
            // start_time is mandatory in the CalendarEventPayload contract (TD-03).
            if (executable.startTime() == null) {
                return Optional.empty();
            }
            return Optional.of(calendarEventPayload(executable));
        }
        return Optional.empty();
    }

    private static ReminderPayload reminderPayload(CoreExecutable executable) {
        return new ReminderPayload(
            executable.name(),
            null,
            executable.endTime(),
            STATUS_DONE.equals(executable.status()),
            0,
            "",
            executable.sourceCalendar() != null ? executable.sourceCalendar() : "");
    }

    private static CalendarEventPayload calendarEventPayload(CoreExecutable executable) {
        return new CalendarEventPayload(
            executable.name(),
            executable.startTime(),
            executable.endTime(),
            false,
            null,
            "",
            executable.sourceCalendar() != null ? executable.sourceCalendar() : "",
            null);
    }

    private static CommandType commandTypeOf(WritePayload payload) {
        return payload instanceof ReminderPayload ? CommandType.REMINDER : CommandType.CALENDAR_EVENT;
    }
}
