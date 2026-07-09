package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.hyperbrain.sync.support.ExecutableSnapshotBuilder.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the ADR-012 D1 write policy: authority matrix + loss-aware projection.
 * Every scenario here reproduces a data-destroying overwrite found in the #15 audit.
 */
@DisplayName("SourceAwareMerge — authority matrix + loss-aware projection (ADR-012 D1)")
class SourceAwareMergeTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CYCLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final OffsetDateTime START =
        OffsetDateTime.parse("2026-07-07T10:00:00-05:00");
    private static final OffsetDateTime DUE =
        OffsetDateTime.parse("2026-07-08T18:00:00-05:00");

    @Nested
    @DisplayName("mergeReminder — Apple authority")
    class MergeReminder {

        @Test
        @DisplayName("a rename in Apple keeps scores and status; unchanged due date preserves startTime")
        void rename_keeps_planning_fields() {
            // current.endTime = DUE (pre-DR-01 state); Apple sends same dueDate = DUE → unchanged
            ExecutableSnapshot current = snapshot().id(ID).userId(USER_ID)
                .name("Old name").status("IN_PROGRESS").priorityScore(0.7)
                .cycleId(CYCLE_ID).parentId(PARENT_ID).isImportant(true)
                .startTime(START).endTime(DUE).sourceCalendar("HyperBrain")
                .energyDrain(3).build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeReminder(current, ID, USER_ID,
                reminder("New name", null, DUE, false, "HyperBrain"));

            // Due date unchanged (DUE == dueProjection) → startTime preserved; endTime always null (DR-01)
            assertThat(merged).usingRecursiveComparison().isEqualTo(new ExecutableSnapshot(
                ID, USER_ID, PARENT_ID, CYCLE_ID, "New name", null, "TASK", "IN_PROGRESS",
                0.7, null, null, true, null, START, null, "HyperBrain", 3, null, null, false));
        }

        @Test
        @DisplayName("completed=false only regresses an actual DONE, never IN_PROGRESS (loss-aware)")
        void completed_flag_is_loss_aware() {
            assertThat(SourceAwareMerge.mergeCompletedFlag("IN_PROGRESS", false)).isEqualTo("IN_PROGRESS");
            assertThat(SourceAwareMerge.mergeCompletedFlag("PLANNED", false)).isEqualTo("PLANNED");
            assertThat(SourceAwareMerge.mergeCompletedFlag("DONE", false)).isEqualTo("TODO");
            assertThat(SourceAwareMerge.mergeCompletedFlag("IN_PROGRESS", true)).isEqualTo("DONE");
            assertThat(SourceAwareMerge.mergeCompletedFlag("DONE", true)).isEqualTo("DONE");
        }

        @Test
        @DisplayName("an unchanged due date (projected as end ?? start) never touches startTime")
        void unchanged_due_date_keeps_times() {
            // Post-DR-01 state: startTime IS the Apple due date; endTime = null.
            ExecutableSnapshot current = snapshot().id(ID).startTime(START).build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeReminder(current, ID, USER_ID,
                reminder("t", null, START, false, "L"));

            assertThat(merged.startTime()).isEqualTo(START);
            assertThat(merged.endTime()).isNull();
        }

        @Test
        @DisplayName("a due date change updates startTime; endTime stays null (DR-01)")
        void due_date_change_lands_on_start_time() {
            // Simulates Daniel changing the reminder time — startTime must update in Notion.
            ExecutableSnapshot current = snapshot().id(ID).startTime(START).build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeReminder(current, ID, USER_ID,
                reminder("t", null, DUE, false, "L"));

            assertThat(merged.startTime()).isEqualTo(DUE);
            assertThat(merged.endTime()).isNull();
        }

        @Test
        @DisplayName("a due date change from a stale Notion startTime updates startTime (regression DR-01)")
        void due_date_change_from_stale_notion_start_updates_start_time() {
            // Reproduces the bug: startTime was a Notion-owned value from 2025; Apple sends a
            // new due date → startTime must update to the new due date so Notion reflects the change.
            OffsetDateTime stale = OffsetDateTime.parse("2025-05-22T15:00:00+00:00");
            ExecutableSnapshot current = snapshot().id(ID).startTime(stale).build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeReminder(current, ID, USER_ID,
                reminder("t", null, DUE, false, "L"));

            assertThat(merged.startTime()).isEqualTo(DUE);
            assertThat(merged.endTime()).isNull();
        }

        @Test
        @DisplayName("clearing the due date in Apple clears startTime")
        void cleared_due_date_clears_start_time() {
            ExecutableSnapshot current = snapshot().id(ID).endTime(DUE).build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeReminder(current, ID, USER_ID,
                reminder("t", null, null, false, "L"));

            assertThat(merged.startTime()).isNull();
            assertThat(merged.endTime()).isNull();
        }

        @Test
        @DisplayName("without a current row the payload creates a plain TASK with dueDate as startTime")
        void creates_task_when_unmapped() {
            ExecutableSnapshot merged = SourceAwareMerge.mergeReminder(null, ID, USER_ID,
                reminder("Buy milk", "notes", DUE, true, "Groceries"));

            assertThat(merged).usingRecursiveComparison().isEqualTo(new ExecutableSnapshot(
                ID, USER_ID, null, null, "Buy milk", "notes", "TASK", "DONE",
                null, null, null, false, null, DUE, null, "Groceries", null, null, null, false));
        }
    }

    @Nested
    @DisplayName("mergeCalendarEvent — Apple authority")
    class MergeCalendarEvent {

        @Test
        @DisplayName("an event update keeps status and type (AGENDA stays AGENDA, no TODO reset)")
        void keeps_status_and_type() {
            ExecutableSnapshot current = snapshot().id(ID).type("AGENDA").status("IN_PROGRESS")
                .impact(4).build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeCalendarEvent(current, ID, USER_ID,
                event("Meeting", START, DUE, "Work"));

            assertThat(merged.type()).isEqualTo("AGENDA");
            assertThat(merged.status()).isEqualTo("IN_PROGRESS");
            assertThat(merged.impact()).isEqualTo(4);
            assertThat(merged.startTime()).isEqualTo(START);
            assertThat(merged.endTime()).isEqualTo(DUE);
            assertThat(merged.sourceCalendar()).isEqualTo("Work");
        }

        @Test
        @DisplayName("without a current row the payload creates an ACTIVITY")
        void creates_activity_when_unmapped() {
            ExecutableSnapshot merged = SourceAwareMerge.mergeCalendarEvent(null, ID, USER_ID,
                event("Meeting", START, DUE, "Work"));

            assertThat(merged.type()).isEqualTo("ACTIVITY");
            assertThat(merged.status()).isEqualTo("TODO");
        }
    }

    @Nested
    @DisplayName("mergeNotionTask — Notion authority")
    class MergeNotionTask {

        @Test
        @DisplayName("editing the name in Notion does not regress PLANNED to TODO (the audit's round-trip bug)")
        void name_edit_does_not_regress_status() {
            ExecutableSnapshot current = snapshot().id(ID).name("Old").status("PLANNED")
                .sourceCalendar("HyperBrain").build();
            // "Not started" is exactly the projection of PLANNED: no information.
            NotionTaskPage page = pageBuilderDefaults("New name", "Not started", false);

            ExecutableSnapshot merged =
                SourceAwareMerge.mergeNotionTask(current, page, ID, USER_ID, null, null);

            assertThat(merged.name()).isEqualTo("New name");
            assertThat(merged.status()).isEqualTo("PLANNED");
            assertThat(merged.sourceCalendar()).isEqualTo("HyperBrain");
        }

        @Test
        @DisplayName("a real status change in Notion is applied")
        void real_status_change_applies() {
            ExecutableSnapshot current = snapshot().id(ID).status("PLANNED").build();

            ExecutableSnapshot merged = SourceAwareMerge.mergeNotionTask(current,
                pageBuilderDefaults("t", "In progress", false), ID, USER_ID, null, null);

            assertThat(merged.status()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("checking Complete moves any status to DONE; unchecking regresses only DONE")
        void complete_checkbox_rules() {
            assertThat(SourceAwareMerge.mergeStatus("IN_PROGRESS", "In progress", true)).isEqualTo("DONE");
            assertThat(SourceAwareMerge.mergeStatus("DONE", "Done", false)).isEqualTo("TODO");
            assertThat(SourceAwareMerge.mergeStatus("WAITING", "Not started", false)).isEqualTo("WAITING");
            assertThat(SourceAwareMerge.mergeStatus("PLANNED", null, null)).isEqualTo("PLANNED");
        }

        @Test
        @DisplayName("an unknown Type option keeps the current type (never guessed); a known one applies")
        void type_merge_rules() {
            assertThat(SourceAwareMerge.mergeType("ACTIVITY", null)).isEqualTo("ACTIVITY");
            assertThat(SourceAwareMerge.mergeType("ACTIVITY", "Activity")).isEqualTo("ACTIVITY");
            assertThat(SourceAwareMerge.mergeType("ACTIVITY", "Someday")).isEqualTo("ACTIVITY");
            assertThat(SourceAwareMerge.mergeType("ACTIVITY", "Habit")).isEqualTo("HABIT");
        }

        @Test
        @DisplayName("a text equal to its 2000-char projection keeps the full original")
        void truncated_text_echo_keeps_original() {
            String full = "x".repeat(2500);
            assertThat(SourceAwareMerge.mergeText(full, "x".repeat(2000))).isEqualTo(full);
            assertThat(SourceAwareMerge.mergeText("old", "new")).isEqualTo("new");
            assertThat(SourceAwareMerge.mergeText("old", null)).isNull();
        }

        @Test
        @DisplayName("a date bound on the same instant keeps the stored representation; a change applies")
        void date_merge_rules() {
            assertThat(SourceAwareMerge.mergeInstant(START, "2026-07-07T10:00:00.000-05:00"))
                .isSameAs(START);
            assertThat(SourceAwareMerge.mergeInstant(START, "2026-07-09"))
                .isEqualTo(OffsetDateTime.parse("2026-07-09T00:00:00-05:00"));
            assertThat(SourceAwareMerge.mergeInstant(START, null)).isNull();
        }

        @Test
        @DisplayName("numbers: null carries no information and keeps the domain-computed value")
        void number_merge_rules() {
            assertThat(SourceAwareMerge.mergeNumber(0.9, null)).isEqualTo(0.9);
            assertThat(SourceAwareMerge.mergeNumber(0.9, 0.4)).isEqualTo(0.4);
            assertThat(SourceAwareMerge.mergeNumber(null, null)).isNull();
        }

        @Test
        @DisplayName("scale selects: null clears, unknown keeps, known maps")
        void scale_merge_rules() {
            assertThat(SourceAwareMerge.mergeScale(3, null, NotionSchema.ENERGY_OPTIONS)).isNull();
            assertThat(SourceAwareMerge.mergeScale(3, "???", NotionSchema.ENERGY_OPTIONS)).isEqualTo(3);
            assertThat(SourceAwareMerge.mergeScale(3, "Intenso", NotionSchema.ENERGY_OPTIONS)).isEqualTo(5);
        }

        @Test
        @DisplayName("an unmapped parent relation keeps the current link; a cleared relation clears it (CA-6)")
        void parent_relation_rules() {
            ExecutableSnapshot current = snapshot().id(ID).parentId(PARENT_ID).build();

            ExecutableSnapshot keptLink = SourceAwareMerge.mergeNotionTask(current,
                page("t", "Not started", false, "parent00000000000000000000000001"),
                ID, USER_ID, null, null);
            ExecutableSnapshot clearedLink = SourceAwareMerge.mergeNotionTask(current,
                page("t", "Not started", false, null), ID, USER_ID, null, null);

            assertThat(keptLink.parentId()).isEqualTo(PARENT_ID);
            assertThat(clearedLink.parentId()).isNull();
        }

        @Test
        @DisplayName("Important null keeps the current flag; an explicit value applies")
        void important_rules() {
            ExecutableSnapshot current = snapshot().id(ID).isImportant(true).build();

            ExecutableSnapshot kept = SourceAwareMerge.mergeNotionTask(current,
                pageBuilderDefaults("t", "Not started", false), ID, USER_ID, null, null);

            assertThat(kept.isImportant()).isTrue();
        }

        @Test
        @DisplayName("without a current row the page creates the executable with the CREATE rules")
        void creates_when_unmapped() {
            ExecutableSnapshot merged = SourceAwareMerge.mergeNotionTask(null,
                pageBuilderDefaults("New task", "In progress", false), ID, USER_ID, CYCLE_ID, null);

            assertThat(merged.name()).isEqualTo("New task");
            assertThat(merged.status()).isEqualTo("IN_PROGRESS");
            assertThat(merged.cycleId()).isEqualTo(CYCLE_ID);
            assertThat(merged.sourceCalendar()).isNull();
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static ReminderPayload reminder(String title, String notes, OffsetDateTime due,
                                            boolean completed, String listName) {
        return new ReminderPayload(title, notes, due, completed, 0, "", listName);
    }

    private static CalendarEventPayload event(String title, OffsetDateTime start,
                                              OffsetDateTime end, String calendarName) {
        return new CalendarEventPayload(title, start, end, false, null, "", calendarName, null);
    }

    private static NotionTaskPage pageBuilderDefaults(String name, String status, Boolean complete) {
        return page(name, status, complete, null);
    }

    private static NotionTaskPage page(String name, String status, Boolean complete,
                                       String parentRelationId) {
        return new NotionTaskPage("page0000000000000000000000000001",
            OffsetDateTime.parse("2026-07-07T15:00:00Z"), false,
            name, null, status, complete, null,
            null, null, null, null, null, null, null,
            null, null, null, null, parentRelationId);
    }
}
