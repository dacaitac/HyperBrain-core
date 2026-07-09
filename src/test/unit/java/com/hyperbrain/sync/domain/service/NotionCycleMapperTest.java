package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CycleSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribute-by-attribute verification of the {@code core_cycle} → Notion Cycles mapping
 * (HU-10 field mapping table, ADR-011: Cycles bidirectional).
 */
@DisplayName("NotionCycleMapper — core_cycle → Notion Cycles properties")
class NotionCycleMapperTest {

    private static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static CycleSnapshot cycle(String type, String status,
                                       LocalDate start, LocalDate end) {
        return new CycleSnapshot(ID, USER_ID, "Sprint 2", type, status, start, end);
    }

    @Nested
    @DisplayName("name → Name (title)")
    class Name {

        @Test
        @DisplayName("maps the cycle name as the title text")
        void maps_name_to_title() {
            Map<String, Object> props =
                NotionCycleMapper.map(cycle("MCI", "ACTIVE", null, null));

            assertThat(props.get("Name")).isEqualTo(
                Map.of("title", List.of(Map.of("text", Map.of("content", "Sprint 2")))));
        }
    }

    @Nested
    @DisplayName("type → Type (select)")
    class Type {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "MCI, MCI",
            "GOAL, Goal",
            "OBJECTIVE, Objective",
            "PROJECT, Project",
            "PHASE, Phase",
            "ROUTINE, Routine"
        })
        @DisplayName("maps every domain cycle type to its Notion select option")
        void maps_type(String domainType, String notionType) {
            Map<String, Object> props =
                NotionCycleMapper.map(cycle(domainType, "ACTIVE", null, null));

            assertThat(props.get("Type"))
                .isEqualTo(Map.of("select", Map.of("name", notionType)));
        }
    }

    @Nested
    @DisplayName("start_date / end_date → Date (date-only range)")
    class Dates {

        @Test
        @DisplayName("maps start and end as a date-only range")
        void maps_date_range() {
            Map<String, Object> props = NotionCycleMapper.map(
                cycle("MCI", "ACTIVE", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14)));

            assertThat(props.get("Date")).isEqualTo(
                Map.of("date", Map.of("start", "2026-07-01", "end", "2026-07-14")));
        }

        @Test
        @DisplayName("start without end produces a single-date value")
        void start_only_has_no_end() {
            Map<String, Object> props = NotionCycleMapper.map(
                cycle("MCI", "ACTIVE", LocalDate.of(2026, 7, 1), null));

            assertThat(props.get("Date"))
                .isEqualTo(Map.of("date", Map.of("start", "2026-07-01")));
        }

        @Test
        @DisplayName("clears Date explicitly when the cycle has no dates (full mirror, ADR-012 D3)")
        void clears_date_when_null() {
            assertThat(NotionCycleMapper.map(cycle("MCI", "ACTIVE", null, null)).get("Date"))
                .isEqualTo(java.util.Collections.singletonMap("date", null));
        }
    }

    @Nested
    @DisplayName("status → Inactive (checkbox, inverted)")
    class Status {

        @Test
        @DisplayName("ACTIVE maps to Inactive=false")
        void active_is_not_inactive() {
            assertThat(NotionCycleMapper.map(cycle("MCI", "ACTIVE", null, null)).get("Inactive"))
                .isEqualTo(Map.of("checkbox", false));
        }

        @Test
        @DisplayName("COMPLETED maps to Inactive=true")
        void completed_is_inactive() {
            assertThat(NotionCycleMapper.map(cycle("MCI", "COMPLETED", null, null)).get("Inactive"))
                .isEqualTo(Map.of("checkbox", true));
        }
    }

    @Nested
    @DisplayName("read-only properties (CA-9)")
    class ReadOnly {

        @Test
        @DisplayName("the mapper never produces formula properties")
        void never_emits_read_only_properties() {
            Map<String, Object> props = NotionCycleMapper.map(
                cycle("PHASE", "COMPLETED", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14)));

            assertThat(props.keySet())
                .doesNotContainAnyElementsOf(NotionSchema.READ_ONLY_PROPERTIES)
                .containsOnly("Name", "Type", "Date", "Inactive");
        }
    }
}
