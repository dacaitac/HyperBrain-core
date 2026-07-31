package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionTaskInboundMapper — Notion → domain (HU-14 CA-5)")
class NotionTaskInboundMapperTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CYCLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PARENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    @DisplayName("maps every attribute of a fully populated page")
    void maps_full_page() {
        // Given
        NotionTaskPage page = new NotionTaskPage(
            "page0000000000000000000000000001",
            OffsetDateTime.of(2026, 7, 7, 15, 0, 0, 0, ZoneOffset.UTC),
            false,
            "Write tests", "Detailed description",
            "In progress", false, "Activity",
            "2026-07-07T10:00:00.000-05:00", "2026-07-07T11:30:00.000-05:00",
            0.8, 0.6, 2.5,
            true, 3.0,
            "High", "Intense", "Routine",
            "cycle000000000000000000000000001", "parent00000000000000000000000001");

        // When
        ExecutableSnapshot snapshot =
            NotionTaskInboundMapper.toSnapshot(page, ID, USER_ID, CYCLE_ID, PARENT_ID);

        // Then
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(new ExecutableSnapshot(
            ID, USER_ID, PARENT_ID, CYCLE_ID,
            "Write tests", "Detailed description", "ACTIVITY", "IN_PROGRESS",
            0.8, 0.6, 2.5,
            true, 3.0,
            OffsetDateTime.parse("2026-07-07T10:00:00-05:00"),
            OffsetDateTime.parse("2026-07-07T11:30:00-05:00"),
            null,
            5, 1, 4, false));
    }

    @ParameterizedTest(name = "Status={0}, Complete={1} → {2}")
    @CsvSource(nullValues = "NULL", value = {
        // DONE when either signal completes (Option B); checkbox checked always wins
        "Done, true, DONE",
        "Done, false, DONE",
        "Done, NULL, DONE",
        "In progress, true, DONE",
        "Not started, true, DONE",
        "Failed, true, DONE",
        "Someday, true, DONE",
        "NULL, true, DONE",
        // Neither signal completed: Status maps directly
        "In progress, false, IN_PROGRESS",
        "In progress, NULL, IN_PROGRESS",
        "Failed, false, FAILED",
        "Failed, NULL, FAILED",
        "Not started, false, TODO",
        "Not started, NULL, TODO",
        // Unknown or missing Status degrades to TODO
        "Someday, false, TODO",
        "NULL, false, TODO",
        "NULL, NULL, TODO"})
    @DisplayName("resolves the domain status under the either-signal-completes policy (Option B)")
    void resolves_status_option_b(String statusName, Boolean complete, String expected) {
        assertThat(NotionTaskInboundMapper.resolveStatus(statusName, complete)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Status=Done with the checkbox off still resolves to DONE (bug #2 fix)")
    void status_done_without_checkbox_is_done() {
        assertThat(NotionTaskInboundMapper.resolveStatus("Done", false)).isEqualTo("DONE");
        // and moving the Status away while the checkbox is off re-opens the task (bug #1 fix)
        assertThat(NotionTaskInboundMapper.resolveStatus("In progress", false)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("an AGENDA page completed via Status (checkbox off) resolves to DONE (status is type-agnostic)")
    void agenda_completed_via_status_is_done() {
        // Given an AGENDA whose Complete checkbox is still off but Status is Done
        NotionTaskPage page = new NotionTaskPage(
            "page0000000000000000000000000003", null, false,
            "Doctor appointment", null, "Done", false, "Agenda",
            null, null, null, null, null,
            null, null,
            null, null, null, null, null);

        // When
        ExecutableSnapshot snapshot = NotionTaskInboundMapper.toSnapshot(page, ID, USER_ID, null, null);

        // Then completion is authoritative regardless of type
        assertThat(snapshot.type()).isEqualTo("AGENDA");
        assertThat(snapshot.status()).isEqualTo("DONE");
    }

    @ParameterizedTest(name = "Type \"{0}\" → {1}")
    @CsvSource({
        "Task, TASK",
        "Habit, HABIT",
        "Lead Measure, LEAD_MEASURE",
        "Activity, ACTIVITY",
        "Agenda, AGENDA",
        "Learning Session, LEARNING_SESSION",
        "Buying, BUYING",
        "Chores, TASK",
        ", TASK"})
    @DisplayName("maps the Type select, degrading unknown options to TASK")
    void maps_type_select(String notionType, String domainType) {
        assertThat(NotionTaskInboundMapper.mapType(notionType)).isEqualTo(domainType);
    }

    @Test
    @DisplayName("scale selects map to their 1-based canonical index; unknown options map to null")
    void maps_scale_selects() {
        assertThat(NotionTaskInboundMapper.scaleOf("Irrelevant", NotionSchema.IMPACT_OPTIONS)).isEqualTo(1);
        assertThat(NotionTaskInboundMapper.scaleOf("Critical", NotionSchema.IMPACT_OPTIONS)).isEqualTo(5);
        assertThat(NotionTaskInboundMapper.scaleOf("Sustained", NotionSchema.ENERGY_OPTIONS)).isEqualTo(3);
        assertThat(NotionTaskInboundMapper.scaleOf("Focus", NotionSchema.MENTAL_LOAD_OPTIONS)).isEqualTo(2);
        assertThat(NotionTaskInboundMapper.scaleOf("Inexistente", NotionSchema.IMPACT_OPTIONS)).isNull();
        assertThat(NotionTaskInboundMapper.scaleOf(null, NotionSchema.IMPACT_OPTIONS)).isNull();
    }

    @Test
    @DisplayName("date-only values anchor at America/Bogota midnight; datetimes keep their offset")
    void parses_notion_dates() {
        assertThat(NotionTaskInboundMapper.parseNotionDate("2026-07-07"))
            .isEqualTo(OffsetDateTime.parse("2026-07-07T00:00:00-05:00"));
        assertThat(NotionTaskInboundMapper.parseNotionDate("2026-07-07T10:00:00.000-05:00"))
            .isEqualTo(OffsetDateTime.parse("2026-07-07T10:00:00-05:00"));
        assertThat(NotionTaskInboundMapper.parseNotionDate(null)).isNull();
        assertThat(NotionTaskInboundMapper.parseNotionDate("not-a-date")).isNull();
    }

    @Test
    @DisplayName("scores are clamped to their DDL check ranges so a manual edit cannot poison the queue")
    void clamps_scores_to_ddl_ranges() {
        // Given a page with out-of-range numbers typed by hand in Notion
        NotionTaskPage page = minimalPage(5.0, 9.9);

        // When
        ExecutableSnapshot snapshot = NotionTaskInboundMapper.toSnapshot(page, ID, USER_ID, null, null);

        // Then priority ∈ [0,1] and effort ∈ [0,5]
        assertThat(snapshot.priorityScore()).isEqualTo(1.0);
        assertThat(snapshot.effortScore()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("missing optional properties map to null; a missing title maps to an empty name")
    void defaults_missing_properties() {
        // Given
        NotionTaskPage page = minimalPage(null, null);

        // When
        ExecutableSnapshot snapshot = NotionTaskInboundMapper.toSnapshot(page, ID, USER_ID, null, null);

        // Then
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(new ExecutableSnapshot(
            ID, USER_ID, null, null, "", null, "TASK", "TODO",
            null, null, null, false, null, null, null, null, null, null, null, false));
    }

    private static NotionTaskPage minimalPage(Double priority, Double effort) {
        return new NotionTaskPage(
            "page0000000000000000000000000002", null, false,
            null, "  ", null, null, null,
            null, null, priority, null, effort,
            null, null,
            null, null, null, null, null);
    }
}
