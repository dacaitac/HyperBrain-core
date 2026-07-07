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
            "Alto", "Intenso", "Rutinario",
            "cycle000000000000000000000000001", "parent00000000000000000000000001");

        // When
        ExecutableSnapshot snapshot =
            NotionTaskInboundMapper.toSnapshot(page, ID, USER_ID, CYCLE_ID, PARENT_ID);

        // Then
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(new ExecutableSnapshot(
            ID, USER_ID, PARENT_ID, CYCLE_ID,
            "Write tests", "Detailed description", "ACTIVITY", "IN_PROGRESS",
            0.8, 0.6, 2.5,
            OffsetDateTime.parse("2026-07-07T10:00:00-05:00"),
            OffsetDateTime.parse("2026-07-07T11:30:00-05:00"),
            5, 1, 4));
    }

    @Test
    @DisplayName("Complete checkbox has priority over Status when resolving the domain status")
    void complete_checkbox_wins_over_status() {
        // Checked → DONE even if Status says otherwise
        assertThat(NotionTaskInboundMapper.resolveStatus("In progress", true)).isEqualTo("DONE");
        assertThat(NotionTaskInboundMapper.resolveStatus(null, true)).isEqualTo("DONE");
        // Unchecked contradicting Status=Done → not done
        assertThat(NotionTaskInboundMapper.resolveStatus("Done", false)).isEqualTo("TODO");
        // Consistent pairs pass through
        assertThat(NotionTaskInboundMapper.resolveStatus("Done", true)).isEqualTo("DONE");
        assertThat(NotionTaskInboundMapper.resolveStatus("Failed", false)).isEqualTo("FAILED");
        assertThat(NotionTaskInboundMapper.resolveStatus("Not started", false)).isEqualTo("TODO");
        // Unknown or missing status degrades to TODO
        assertThat(NotionTaskInboundMapper.resolveStatus("Someday", false)).isEqualTo("TODO");
        assertThat(NotionTaskInboundMapper.resolveStatus(null, null)).isEqualTo("TODO");
    }

    @ParameterizedTest(name = "Type \"{0}\" → {1}")
    @CsvSource({
        "Task, TASK",
        "Habit, HABIT",
        "Lead Measure, LEAD_MEASURE",
        "Activity, ACTIVITY",
        "Agenda, AGENDA",
        "Learning Session, LEARNING_SESSION",
        "Buying, TASK",
        ", TASK"})
    @DisplayName("maps the Type select, degrading unknown options to TASK")
    void maps_type_select(String notionType, String domainType) {
        assertThat(NotionTaskInboundMapper.mapType(notionType)).isEqualTo(domainType);
    }

    @Test
    @DisplayName("scale selects map to their 1-based canonical index; unknown options map to null")
    void maps_scale_selects() {
        assertThat(NotionTaskInboundMapper.scaleOf("Irrelevante", NotionSchema.IMPACT_OPTIONS)).isEqualTo(1);
        assertThat(NotionTaskInboundMapper.scaleOf("Crítico", NotionSchema.IMPACT_OPTIONS)).isEqualTo(5);
        assertThat(NotionTaskInboundMapper.scaleOf("Sostenido", NotionSchema.ENERGY_OPTIONS)).isEqualTo(3);
        assertThat(NotionTaskInboundMapper.scaleOf("Foco", NotionSchema.MENTAL_LOAD_OPTIONS)).isEqualTo(2);
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
            null, null, null, null, null, null, null, null));
    }

    private static NotionTaskPage minimalPage(Double priority, Double effort) {
        return new NotionTaskPage(
            "page0000000000000000000000000002", null, false,
            null, "  ", null, null, null,
            null, null, priority, null, effort,
            null, null, null, null, null);
    }
}
