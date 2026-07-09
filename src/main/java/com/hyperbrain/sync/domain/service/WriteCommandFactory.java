package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.model.WritePayload;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds outbound {@link WriteCommand}s from a {@link CoreExecutable} — the inverse of the
 * HU-09 inbound mapping (CA-5, HU-09c). Pure domain logic; the caller supplies identifiers
 * and decides the effective operation.
 *
 * <p>Write-back type mapping (single source of truth: {@link #APPLE_COMMAND_TYPES}):
 * {@code TASK}, {@code HABIT}, {@code LEAD_MEASURE} → {@code REMINDER};
 * {@code ACTIVITY}, {@code LEARNING_SESSION} → {@code CALENDAR_EVENT}.
 * {@code AGENDA} is read-only by contract (ADR-009) and any other type has no Apple
 * counterpart — both are skipped.
 *
 * <p>The reminder due date is the executable {@code start_time} (a reminder is due at its start;
 * {@code end_time} is cleared for reminder types upstream by DR-01). Whether a due/start is
 * all-day or timed is <em>not</em> carried explicitly: SentinelAPI derives it at the Apple
 * boundary from whether the instant falls on local midnight (no time-of-day ⇒ all-day).
 *
 * <p>The Core does not persist EventKit list/calendar identifiers, so {@code list_id} /
 * {@code calendar_id} travel empty and {@code list_name} / {@code calendar_name} carry
 * {@code source_calendar}; SentinelAPI resolves the target list by name or falls back to
 * the default one.
 */
public final class WriteCommandFactory {

    /** Executable type → Apple entity kind. Absent types are not written back to Apple. */
    private static final Map<String, CommandType> APPLE_COMMAND_TYPES = Map.of(
        "TASK", CommandType.REMINDER,
        "HABIT", CommandType.REMINDER,
        "LEAD_MEASURE", CommandType.REMINDER,
        "ACTIVITY", CommandType.CALENDAR_EVENT,
        "LEARNING_SESSION", CommandType.CALENDAR_EVENT);

    private static final String STATUS_DONE = "DONE";

    private WriteCommandFactory() {}

    /**
     * Resolves the Apple entity kind an executable type writes back to.
     *
     * @param executableType the {@code core_executable.type} value (may be null)
     * @return the command type, or empty when the type is read-only (AGENDA) or has no
     *         Apple counterpart
     */
    public static Optional<CommandType> commandTypeForExecutableType(String executableType) {
        return Optional.ofNullable(executableType).map(APPLE_COMMAND_TYPES::get);
    }

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
     * read-only (ADR-009) and types without an Apple counterpart are simply not writable.
     *
     * @param executableType the {@code core_executable.type} value
     * @return true for the reminder types (TASK, HABIT, LEAD_MEASURE) and event types
     *         (ACTIVITY, LEARNING_SESSION)
     */
    public static boolean isWritable(String executableType) {
        return commandTypeForExecutableType(executableType).isPresent();
    }

    private static Optional<WritePayload> payloadFor(CoreExecutable executable) {
        Optional<CommandType> commandType = commandTypeForExecutableType(executable.type());
        if (commandType.isEmpty()) {
            return Optional.empty();
        }
        if (commandType.get() == CommandType.REMINDER) {
            return Optional.of(reminderPayload(executable));
        }
        // CALENDAR_EVENT: start_time is mandatory in the CalendarEventPayload contract (TD-03).
        if (executable.startTime() == null) {
            return Optional.empty();
        }
        return Optional.of(calendarEventPayload(executable));
    }

    private static ReminderPayload reminderPayload(CoreExecutable executable) {
        // A reminder is due at its start_time (HU-09c); end_time is cleared for reminder types
        // upstream (DR-01). All-day vs timed is derived downstream by SentinelAPI.
        OffsetDateTime dueDate = executable.startTime();
        return new ReminderPayload(
            executable.name(),
            executable.description(),
            dueDate,
            STATUS_DONE.equals(executable.status()),
            0,
            "",
            executable.sourceCalendar() != null ? executable.sourceCalendar() : "");
    }

    private static CalendarEventPayload calendarEventPayload(CoreExecutable executable) {
        // all_day is left false here: SentinelAPI derives it at the Apple boundary from whether
        // start/end fall on local midnight (no time-of-day ⇒ all-day), the same rule reminders use.
        return new CalendarEventPayload(
            executable.name(),
            executable.startTime(),
            executable.endTime(),
            false,
            executable.description(),
            "",
            executable.sourceCalendar() != null ? executable.sourceCalendar() : "",
            null);
    }

    private static CommandType commandTypeOf(WritePayload payload) {
        return payload instanceof ReminderPayload ? CommandType.REMINDER : CommandType.CALENDAR_EVENT;
    }
}
