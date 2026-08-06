package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionTimeBlockMapper — Time Blocks property mapping (ADR-038)")
class NotionTimeBlockMapperTest {

    private static final UUID BLOCK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    // 2026-08-05 is a Wednesday; 09:00–10:30 local Bogota (-05:00).
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime END = START.plusMinutes(90);

    @Test
    @DisplayName("composed title: 'Mié 05 · 09:00–10:30 · <theme>' in the user's local day")
    void composes_title_with_local_day_window_and_theme() {
        // Given
        TimeBlockSnapshot block = block("Deep work", List.of(member(MEMBER_A, "Task A", 30, 0)));

        // When
        String title = NotionTimeBlockMapper.composeTitle(block);

        // Then
        assertThat(title).isEqualTo("Mié 05 · 09:00–10:30 · Deep work");
    }

    @Test
    @DisplayName("title theme falls back to the anchor member's name when the theme is null (ADR-027 D1)")
    void title_falls_back_to_anchor_name() {
        // Given
        TimeBlockSnapshot block = block(null, List.of(
            member(MEMBER_A, "Write report", 45, 0), member(MEMBER_B, "Other", 45, 1)));

        // When / Then
        assertThat(NotionTimeBlockMapper.composeTitle(block))
            .isEqualTo("Mié 05 · 09:00–10:30 · Write report");
    }

    @Test
    @DisplayName("extractTheme recovers the theme from a composed title and round-trips composeTitle")
    void extract_theme_round_trips() {
        // Given
        TimeBlockSnapshot block = block("Focus: writing", List.of(member(MEMBER_A, "A", 30, 0)));
        String composed = NotionTimeBlockMapper.composeTitle(block);

        // When / Then
        assertThat(NotionTimeBlockMapper.extractTheme(composed)).isEqualTo("Focus: writing");
    }

    @Test
    @DisplayName("extractTheme treats a fully rewritten title as all-theme")
    void extract_theme_free_title_is_all_theme() {
        assertThat(NotionTimeBlockMapper.extractTheme("My own block name"))
            .isEqualTo("My own block name");
        assertThat(NotionTimeBlockMapper.extractTheme(null)).isNull();
        assertThat(NotionTimeBlockMapper.extractTheme("  ")).isNull();
    }

    @Test
    @DisplayName("Agenda line lists members in ord order: '1. A — 30 min · 2. B — 45 min'")
    void agenda_line_orders_by_ord() {
        // Given members deliberately out of ord order
        List<TimeBlockMemberSnapshot> members = List.of(
            member(MEMBER_B, "Task B", 45, 1), member(MEMBER_A, "Task A", 30, 0));

        // When / Then
        assertThat(NotionTimeBlockMapper.agendaLine(members))
            .isEqualTo("1. Task A — 30 min · 2. Task B — 45 min");
        assertThat(NotionTimeBlockMapper.agendaLine(List.of())).isNull();
    }

    @Test
    @DisplayName("canonical map: every writable property present, statuses/origins mapped, no read-only property")
    void canonical_map_is_complete_and_writable() {
        // Given
        TimeBlockSnapshot block = block("Theme", List.of(member(MEMBER_A, "Task A", 90, 0)));

        // When
        Map<String, Object> props =
            NotionTimeBlockMapper.map(block, List.of("page000000000000000000000000000a"), null);

        // Then — insertion-ordered canonical map with the full ADR-038 property set
        assertThat(props.keySet()).containsExactly(
            "Name", "Date", "Status", "Origin", "Planned Minutes", "Actual Minutes",
            "Agenda", "Reason", "Sync Note", "Tasks");
        assertThat(props.get("Status")).isEqualTo(Map.of("select", Map.of("name", "Planned")));
        assertThat(props.get("Origin")).isEqualTo(Map.of("select", Map.of("name", "Planner")));
        assertThat(props.get("Sync Note")).isEqualTo(Map.of("rich_text", List.of()));
        assertThat(props).doesNotContainKeys("Cycles", "Time Blocks");
    }

    @Test
    @DisplayName("status and origin selects map every domain value; FOCUS never reaches the mapper by contract")
    void status_and_origin_options() {
        assertThat(NotionTimeBlockMapper.mapStatus("PLANNED")).isEqualTo("Planned");
        assertThat(NotionTimeBlockMapper.mapStatus("ACTIVE")).isEqualTo("Active");
        assertThat(NotionTimeBlockMapper.mapStatus("SETTLED")).isEqualTo("Settled");
        assertThat(NotionTimeBlockMapper.mapStatus("EXPIRED")).isEqualTo("Expired");
        assertThat(NotionTimeBlockMapper.mapOrigin("PLANNER")).isEqualTo("Planner");
        assertThat(NotionTimeBlockMapper.mapOrigin("USER")).isEqualTo("User");
    }

    @Test
    @DisplayName("Tasks relation is canonical: page ids serialized sorted ascending regardless of input order")
    void relation_is_canonically_sorted() {
        // Given
        TimeBlockSnapshot block = block("T", List.of(member(MEMBER_A, "A", 10, 0)));

        // When
        Map<String, Object> shuffled = NotionTimeBlockMapper.map(block,
            List.of("bbbb0000000000000000000000000002", "aaaa0000000000000000000000000001"), null);

        // Then
        assertThat(shuffled.get("Tasks")).isEqualTo(Map.of("relation", List.of(
            Map.of("id", "aaaa0000000000000000000000000001"),
            Map.of("id", "bbbb0000000000000000000000000002"))));
    }

    private static TimeBlockSnapshot block(String theme, List<TimeBlockMemberSnapshot> members) {
        return new TimeBlockSnapshot(BLOCK_ID, USER_ID, START, END, "PLANNED", "PLANNER",
            theme, "because", 90, null, null, members);
    }

    private static TimeBlockMemberSnapshot member(UUID id, String name, int minutes, int ord) {
        return new TimeBlockMemberSnapshot(id, name, minutes, ord);
    }
}
