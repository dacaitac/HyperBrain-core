package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.model.WritePayload;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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

    /** Wall-clock format of the attribution marker's hour (24 h, no seconds). */
    private static final DateTimeFormatter MARKER_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Visible, non-alarming attribution the write-back adds to a reminder whose due date the Planner set
     * (core#50, Part C): an informative "scheduled by HyperBrain at HH:MM", never a guilt alarm.
     */
    private static final String SCHEDULED_MARKER_PREFIX = "⏱ Programado por HyperBrain ";

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
        return forUpsert(commandId, executable, operation, entityId, Optional.empty());
    }

    /**
     * Builds a CREATED/UPDATED command whose reminder due date is the hour the Planner scheduled the
     * executable (core#50, Part C), projected indirectly from {@code core_time_block} — never from
     * {@code core_executable.start_time}, which stays authored-only (ADR-026 D3).
     *
     * <p><b>Authority (placeholder-only).</b> The scheduled hour is asserted onto the reminder <em>only
     * when the executable's own start time is a placeholder</em> — null, or midnight (an all-day date
     * with no time-of-day the user picked). A start time the user set with intent is never overwritten,
     * and it survives the replan (source-aware merge, ADR-012). When the scheduled hour is used, the
     * reminder carries a visible, non-alarming attribution marker in its notes.
     *
     * @param commandId       correlation id for the command
     * @param executable      current state of the {@code core_executable} row
     * @param operation       {@code CREATED} or {@code UPDATED}
     * @param entityId        EventKit identifier for UPDATED; {@code null} for CREATED
     * @param scheduledStart  the Planner's scheduled block start for this executable (from
     *                        {@code ScheduledDueTimeProvider}); empty when it holds no live planner block
     * @return the command, or empty if the executable must not be written to Apple
     */
    public static Optional<WriteCommand> forUpsert(
        UUID commandId, CoreExecutable executable, Operation operation, String entityId,
        Optional<OffsetDateTime> scheduledStart) {
        return payloadFor(executable, scheduledStart).map(payload -> new WriteCommand(
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

    private static Optional<WritePayload> payloadFor(
        CoreExecutable executable, Optional<OffsetDateTime> scheduledStart) {
        Optional<CommandType> commandType = commandTypeForExecutableType(executable.type());
        if (commandType.isEmpty()) {
            return Optional.empty();
        }
        if (commandType.get() == CommandType.REMINDER) {
            return Optional.of(reminderPayload(executable, scheduledStart));
        }
        // CALENDAR_EVENT: start_time is mandatory in the CalendarEventPayload contract (TD-03). The
        // Planner's scheduled hour does not apply to calendar-event types (ACTIVITY/LEARNING keep their
        // authored window), so scheduledStart is ignored here.
        if (executable.startTime() == null) {
            return Optional.empty();
        }
        return Optional.of(calendarEventPayload(executable));
    }

    private static ReminderPayload reminderPayload(
        CoreExecutable executable, Optional<OffsetDateTime> scheduledStart) {
        // Placeholder-only authority (ADR-026 D3, core#50 Part C): the reminder is due at the hour the
        // Planner scheduled the executable ONLY when the user did not pick a time — a null or midnight
        // start_time is a placeholder the system may fill; any other start_time is the user's intent and
        // is never overwritten. end_time is cleared for reminder types upstream (DR-01). All-day vs timed
        // is derived downstream by SentinelAPI.
        OffsetDateTime authored = executable.startTime();
        boolean placeholder = isPlaceholder(authored);
        boolean systemScheduled = placeholder && scheduledStart.isPresent();
        OffsetDateTime dueDate = systemScheduled ? scheduledStart.get() : authored;
        String notes = systemScheduled
            ? withAttributionMarker(executable.description(), dueDate)
            : executable.description();
        return new ReminderPayload(
            executable.name(),
            notes,
            dueDate,
            STATUS_DONE.equals(executable.status()),
            0,
            "",
            executable.sourceCalendar() != null ? executable.sourceCalendar() : "");
    }

    /**
     * Whether an authored start time is a placeholder the Planner may fill: absent, or local midnight (an
     * all-day date the user gave no time-of-day). Mirrors the all-day derivation SentinelAPI uses and the
     * tz-session criterion of ADR-026 — a time the user genuinely set is never midnight-exact by accident.
     */
    private static boolean isPlaceholder(OffsetDateTime startTime) {
        return startTime == null || startTime.toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    /**
     * Prepends the visible, non-alarming scheduling attribution to the reminder notes (core#50): an
     * informative line so Daniel sees the hour is HyperBrain's, keeping any user notes below it.
     *
     * <p>Idempotent: any previous marker line (from a prior write-back that round-tripped back through
     * an inbound Apple sync into the description) is stripped first, so the marker never accumulates.
     */
    private static String withAttributionMarker(String notes, OffsetDateTime dueDate) {
        String marker = SCHEDULED_MARKER_PREFIX + dueDate.toLocalTime().format(MARKER_TIME);
        String userNotes = stripExistingMarker(notes);
        return userNotes.isBlank() ? marker : marker + "\n\n" + userNotes;
    }

    /** Removes a leading attribution marker (and its trailing blank line) so it never stacks up. */
    private static String stripExistingMarker(String notes) {
        if (notes == null || !notes.startsWith(SCHEDULED_MARKER_PREFIX)) {
            return notes == null ? "" : notes;
        }
        int separator = notes.indexOf("\n\n");
        return separator < 0 ? "" : notes.substring(separator + 2);
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
