package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WriteCommandFactory")
class WriteCommandFactoryTest {

    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 7, 6, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = OffsetDateTime.of(2026, 7, 6, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("TASK maps to a REMINDER command with the inverse HU-09 field mapping")
    void task_maps_to_reminder_command() {
        // Given
        CoreExecutable task = executable("TASK", "DONE", null, END, "HyperBrain");

        // When
        Optional<WriteCommand> command = WriteCommandFactory.forUpsert(
            COMMAND_ID, task, Operation.UPDATED, "EK-1");

        // Then
        assertThat(command).isPresent();
        assertThat(command.get()).usingRecursiveComparison().isEqualTo(new WriteCommand(
            COMMAND_ID, CommandType.REMINDER, Operation.UPDATED, "EK-1",
            new ReminderPayload("Buy groceries", "2L milk", END, true, 0, "", "HyperBrain")));
    }

    @Test
    @DisplayName("TASK with status other than DONE maps to completed=false")
    void task_not_done_maps_to_not_completed() {
        // Given
        CoreExecutable task = executable("TASK", "TODO", null, null, null);

        // When
        Optional<WriteCommand> command = WriteCommandFactory.forUpsert(
            COMMAND_ID, task, Operation.CREATED, null);

        // Then
        assertThat(command).isPresent();
        ReminderPayload payload = (ReminderPayload) command.get().payload();
        assertThat(payload.completed()).isFalse();
        assertThat(payload.dueDate()).isNull();
        assertThat(payload.listName()).isEmpty();
    }

    @Test
    @DisplayName("ACTIVITY maps to a CALENDAR_EVENT command carrying start/end times")
    void activity_maps_to_calendar_event_command() {
        // Given
        CoreExecutable activity = executable("ACTIVITY", "TODO", START, END, "Personal");

        // When
        Optional<WriteCommand> command = WriteCommandFactory.forUpsert(
            COMMAND_ID, activity, Operation.CREATED, null);

        // Then
        assertThat(command).isPresent();
        assertThat(command.get()).usingRecursiveComparison().isEqualTo(new WriteCommand(
            COMMAND_ID, CommandType.CALENDAR_EVENT, Operation.CREATED, null,
            new CalendarEventPayload("Buy groceries", START, END, false, "2L milk", "", "Personal", null)));
    }

    @Test
    @DisplayName("ACTIVITY without start_time is not representable (contract requires it)")
    void activity_without_start_time_is_rejected() {
        // Given
        CoreExecutable activity = executable("ACTIVITY", "TODO", null, END, "Personal");

        // When / Then
        assertThat(WriteCommandFactory.forUpsert(COMMAND_ID, activity, Operation.CREATED, null)).isEmpty();
    }

    @Test
    @DisplayName("AGENDA never produces a command (read-only per ADR-009)")
    void agenda_is_rejected() {
        // Given
        CoreExecutable agenda = executable("AGENDA", "TODO", START, END, "Google");

        // When / Then
        assertThat(WriteCommandFactory.forUpsert(COMMAND_ID, agenda, Operation.UPDATED, "EK-1")).isEmpty();
        assertThat(WriteCommandFactory.isWritable("AGENDA")).isFalse();
    }

    @Test
    @DisplayName("types without an Apple counterpart produce no command")
    void unsupported_types_are_rejected() {
        for (String type : new String[] {"HABIT", "LEAD_MEASURE", "LEARNING_SESSION"}) {
            CoreExecutable executable = executable(type, "TODO", START, END, null);
            assertThat(WriteCommandFactory.forUpsert(COMMAND_ID, executable, Operation.CREATED, null))
                .as("type %s", type)
                .isEmpty();
            assertThat(WriteCommandFactory.isWritable(type)).as("type %s", type).isFalse();
        }
    }

    @Test
    @DisplayName("only TASK and ACTIVITY are writable")
    void writable_types() {
        assertThat(WriteCommandFactory.isWritable("TASK")).isTrue();
        assertThat(WriteCommandFactory.isWritable("ACTIVITY")).isTrue();
    }

    @Test
    @DisplayName("forDelete builds a DELETED command without payload")
    void for_delete_builds_deleted_command() {
        // When
        WriteCommand command = WriteCommandFactory.forDelete(COMMAND_ID, CommandType.CALENDAR_EVENT, "EK-9");

        // Then
        assertThat(command).usingRecursiveComparison().isEqualTo(new WriteCommand(
            COMMAND_ID, CommandType.CALENDAR_EVENT, Operation.DELETED, "EK-9", null));
    }

    private static CoreExecutable executable(
        String type, String status, OffsetDateTime start, OffsetDateTime end, String sourceCalendar) {
        return new CoreExecutable(
            UUID.randomUUID(), USER_ID, "Buy groceries", "2L milk", type, status, start, end, sourceCalendar);
    }
}
