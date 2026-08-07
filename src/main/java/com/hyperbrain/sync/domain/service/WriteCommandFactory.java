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
 * {@code TASK}, {@code HABIT}, {@code LEAD_MEASURE}, {@code BUYING} → {@code REMINDER};
 * {@code ACTIVITY}, {@code LEARNING_SESSION}, {@code TIME_BLOCK} and {@code AGENDA} →
 * {@code CALENDAR_EVENT}. {@code AGENDA} became bidirectional in core#65 (writable to its own
 * "DanielC" Google calendar, no longer read-only); any type absent from the map has no Apple
 * counterpart and is skipped. Calendar-event destinations are config-driven per type (core#64).
 *
 * <p>The reminder due date is the executable {@code start_time} (a reminder is due at its start;
 * {@code end_time} is cleared for reminder types upstream by DR-01). Whether a due/start is
 * all-day or timed is <em>not</em> carried explicitly: SentinelAPI derives it at the Apple
 * boundary from whether the instant falls on local midnight (no time-of-day ⇒ all-day).
 *
 * <p>The Core does not persist EventKit list/calendar identifiers, so {@code list_id} /
 * {@code calendar_id} travel empty and {@code list_name} / {@code calendar_name} carry
 * {@code source_calendar}; SentinelAPI resolves the target list by name or falls back to
 * the default one. The one exception is {@code BUYING}: shopping items are gathered in a
 * dedicated {@link #BUYING_LIST_NAME} list regardless of their source, so SentinelAPI
 * resolve-or-creates that list, and a type transition into or out of {@code BUYING}
 * re-targets the reminder's list (moved on update by SentinelAPI).
 */
public final class WriteCommandFactory {

    /** Executable type → Apple entity kind. Absent types are not written back to Apple. */
    private static final Map<String, CommandType> APPLE_COMMAND_TYPES = Map.of(
        "TASK", CommandType.REMINDER,
        "HABIT", CommandType.REMINDER,
        "LEAD_MEASURE", CommandType.REMINDER,
        "BUYING", CommandType.REMINDER,
        "ACTIVITY", CommandType.CALENDAR_EVENT,
        "LEARNING_SESSION", CommandType.CALENDAR_EVENT,
        // ADR-039: after the TimeBlock collapse a block IS an executable; it write-backs as a
        // time-boxed EKEvent (start_time is always present on a block). Its destination calendar is
        // config-driven per type (core#64): "Trabajo" by default, resolved in calendarNameFor.
        "TIME_BLOCK", CommandType.CALENDAR_EVENT,
        // core#65: AGENDA is now BIDIRECTIONAL — writable to its own named calendar ("DanielC",
        // a Google calendar verified writable via EventKit), no longer read-only. It rides the same
        // CALENDAR_EVENT path as ACTIVITY; the destination calendar is config-driven (core#64).
        "AGENDA", CommandType.CALENDAR_EVENT);

    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";

    /** Executable type whose reminders are gathered in the dedicated shopping list. */
    private static final String BUYING_TYPE = "BUYING";

    /**
     * Reminders list that gathers shopping items ({@code BUYING}), independent of their source
     * list. SentinelAPI resolve-or-creates it by name, mirroring the managed event calendar.
     */
    private static final String BUYING_LIST_NAME = "Compras";

    private WriteCommandFactory() {}

    /**
     * Resolves the Apple entity kind an executable type writes back to.
     *
     * @param executableType the {@code core_executable.type} value (may be null)
     * @return the command type, or empty when the type has no Apple counterpart
     */
    public static Optional<CommandType> commandTypeForExecutableType(String executableType) {
        return Optional.ofNullable(executableType).map(APPLE_COMMAND_TYPES::get);
    }

    /**
     * Builds a CREATED/UPDATED command for an executable, or empty when the executable is not
     * writable to Apple (an unsupported type, or a calendar event without {@code startTime} —
     * required by the EventKit contract).
     *
     * @param commandId  correlation id for the command
     * @param executable current state of the {@code core_executable} row
     * @param operation  {@code CREATED} or {@code UPDATED}
     * @param entityId   EventKit identifier for UPDATED; {@code null} for CREATED
     * @return the command, or empty if the executable must not be written to Apple
     */
    public static Optional<WriteCommand> forUpsert(
        UUID commandId, CoreExecutable executable, Operation operation, String entityId) {
        return forUpsert(commandId, executable, operation, entityId, Map.of());
    }

    /**
     * Builds a CREATED/UPDATED command, routing calendar-event types to their configured
     * destination calendar (core#64). {@code calendarNamesByType} maps an executable type to the
     * named calendar its EKEvent must land on (ADR-009, configurable); a type absent from the map
     * keeps the {@code source_calendar} default. Reminder types ignore the map.
     *
     * @param commandId           correlation id for the command
     * @param executable          current state of the {@code core_executable} row
     * @param operation           {@code CREATED} or {@code UPDATED}
     * @param entityId            EventKit identifier for UPDATED; {@code null} for CREATED
     * @param calendarNamesByType type → destination calendar name (never null; may be empty)
     * @return the command, or empty if the executable must not be written to Apple
     */
    public static Optional<WriteCommand> forUpsert(
        UUID commandId, CoreExecutable executable, Operation operation, String entityId,
        Map<String, String> calendarNamesByType) {
        return payloadFor(executable, calendarNamesByType).map(payload -> new WriteCommand(
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
     * Returns whether an executable type is eligible for write-back at all. Since core#65
     * {@code AGENDA} is writable too (bidirectional to its "DanielC" calendar); only types with no
     * Apple counterpart are non-writable.
     *
     * @param executableType the {@code core_executable.type} value
     * @return true for the reminder types (TASK, HABIT, LEAD_MEASURE, BUYING) and the event types
     *         (ACTIVITY, LEARNING_SESSION, TIME_BLOCK, AGENDA)
     */
    public static boolean isWritable(String executableType) {
        return commandTypeForExecutableType(executableType).isPresent();
    }

    private static Optional<WritePayload> payloadFor(CoreExecutable executable,
                                                     Map<String, String> calendarNamesByType) {
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
        return Optional.of(calendarEventPayload(executable, calendarNamesByType));
    }

    private static ReminderPayload reminderPayload(CoreExecutable executable) {
        // A reminder is due at its start_time (HU-09c); end_time is cleared for reminder types
        // upstream (DR-01). All-day vs timed is derived downstream by SentinelAPI.
        OffsetDateTime dueDate = executable.startTime();
        return new ReminderPayload(
            executable.name(),
            executable.description(),
            dueDate,
            isClosed(executable.status()),
            0,
            "",
            reminderListName(executable));
    }

    /**
     * Projects a domain status onto EventKit's single completion flag: a reminder is checked off
     * when the executable left the inventory, {@code DONE} <b>or</b> {@code FAILED} (ADR-039's two
     * predicates, spelled out by ADR-040 D4). Marking something as a sanctioned miss deletes
     * nothing on the Apple side — the reminder is simply <em>closed</em> and stops showing among
     * the active ones, which is the whole point of failing yesterday's habit.
     *
     * @param status the {@code core_executable.status} value
     * @return the {@code completed} flag to write
     */
    private static boolean isClosed(String status) {
        return STATUS_DONE.equals(status) || STATUS_FAILED.equals(status);
    }

    /**
     * Resolves the target reminders list name. {@code BUYING} executables always go to the
     * dedicated {@link #BUYING_LIST_NAME} list regardless of their {@code source_calendar}, so
     * shopping items stay grouped; every other reminder type keeps its {@code source_calendar}
     * (empty when unknown, letting SentinelAPI fall back to the default list).
     */
    private static String reminderListName(CoreExecutable executable) {
        if (BUYING_TYPE.equals(executable.type())) {
            return BUYING_LIST_NAME;
        }
        return executable.sourceCalendar() != null ? executable.sourceCalendar() : "";
    }

    private static CalendarEventPayload calendarEventPayload(CoreExecutable executable,
                                                             Map<String, String> calendarNamesByType) {
        // all_day is left false here: SentinelAPI derives it at the Apple boundary from whether
        // start/end fall on local midnight (no time-of-day ⇒ all-day), the same rule reminders use.
        return new CalendarEventPayload(
            executable.name(),
            executable.startTime(),
            executable.endTime(),
            false,
            executable.description(),
            "",
            calendarNameFor(executable, calendarNamesByType),
            null);
    }

    /**
     * Resolves the destination calendar name for a calendar-event executable (core#64): the
     * configured per-type name (ACTIVITY/LEARNING_SESSION → "HyperBrain", TIME_BLOCK → "Trabajo")
     * takes precedence; a type absent from the map keeps the {@code source_calendar} default so
     * pre-existing behaviour is preserved.
     */
    private static String calendarNameFor(CoreExecutable executable,
                                          Map<String, String> calendarNamesByType) {
        String configured = calendarNamesByType.get(executable.type());
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return executable.sourceCalendar() != null ? executable.sourceCalendar() : "";
    }

    private static CommandType commandTypeOf(WritePayload payload) {
        return payload instanceof ReminderPayload ? CommandType.REMINDER : CommandType.CALENDAR_EVENT;
    }
}
