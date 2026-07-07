package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static com.hyperbrain.sync.support.ExecutableSnapshotBuilder.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribute-by-attribute verification of the {@code core_executable} → Notion Tasks mapping
 * (HU-10 field mapping table + ADR-012 D3 full-mirror clears). Each nested block covers one
 * domain attribute.
 */
@DisplayName("NotionTaskMapper — core_executable → Notion Tasks properties")
class NotionTaskMapperTest {

    /** 2026-07-06 14:30 UTC = 09:30 in America/Bogota (UTC-5). */
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 7, 6, 14, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END =
        OffsetDateTime.of(2026, 7, 6, 16, 0, 0, 0, ZoneOffset.UTC);
    /** 05:00 UTC = local midnight in America/Bogota. */
    private static final OffsetDateTime BOGOTA_MIDNIGHT =
        OffsetDateTime.of(2026, 7, 6, 5, 0, 0, 0, ZoneOffset.UTC);

    private static ExecutableSnapshot fullSnapshot() {
        return snapshot().name("Write tests").description("A description")
            .priorityScore(0.8).urgencyScore(0.5).effortScore(3.0)
            .isImportant(true).frequency(2.0)
            .startTime(START).endTime(END)
            .energyDrain(3).mentalLoad(2).impact(4)
            .build();
    }

    private static ExecutableSnapshot minimalSnapshot() {
        return snapshot().name("Bare task").build();
    }

    private static Map<String, Object> map(ExecutableSnapshot snapshot) {
        return NotionTaskMapper.map(snapshot, null, null);
    }

    @SuppressWarnings("unchecked")
    private static String selectName(Map<String, Object> props, String property) {
        Map<String, Object> select = (Map<String, Object>) props.get(property);
        return ((Map<String, String>) select.get("select")).get("name");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dateValue(Map<String, Object> props) {
        return (Map<String, Object>) ((Map<String, Object>) props.get("Date")).get("date");
    }

    @Nested
    @DisplayName("name → Name (title)")
    class Name {

        @Test
        @DisplayName("maps the executable name as the title text")
        void maps_name_to_title() {
            Map<String, Object> props = map(fullSnapshot());

            assertThat(props.get("Name")).isEqualTo(
                Map.of("title", List.of(Map.of("text", Map.of("content", "Write tests")))));
        }

        @Test
        @DisplayName("truncates names beyond Notion's 2000-character limit")
        void truncates_long_name() {
            Map<String, Object> props = map(snapshot().name("x".repeat(2500)).build());

            String content = extractTitle(props);
            assertThat(content).hasSize(2000);
        }

        @SuppressWarnings("unchecked")
        private String extractTitle(Map<String, Object> props) {
            List<Map<String, Object>> title =
                (List<Map<String, Object>>) ((Map<String, Object>) props.get("Name")).get("title");
            return ((Map<String, String>) title.get(0).get("text")).get("content");
        }
    }

    @Nested
    @DisplayName("description → Description (rich_text)")
    class Description {

        @Test
        @DisplayName("maps the description as rich text")
        void maps_description() {
            Map<String, Object> props = map(fullSnapshot());

            assertThat(props.get("Description")).isEqualTo(
                Map.of("rich_text", List.of(Map.of("text", Map.of("content", "A description")))));
        }

        @Test
        @DisplayName("clears Description explicitly when the domain value is null (full mirror, ADR-012 D3)")
        void clears_null_description() {
            assertThat(map(minimalSnapshot()).get("Description"))
                .isEqualTo(Map.of("rich_text", List.of()));
        }
    }

    @Nested
    @DisplayName("status → Status (status) + Complete (checkbox)")
    class Status {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "TODO, Not started",
            "PLANNED, Not started",
            "WAITING, Not started",
            "IN_PROGRESS, In progress",
            "DONE, Done",
            "FAILED, Failed"
        })
        @DisplayName("maps every domain status to its Notion status option")
        void maps_status(String domainStatus, String notionStatus) {
            Map<String, Object> props = map(snapshot().status(domainStatus).build());

            assertThat(props.get("Status"))
                .isEqualTo(Map.of("status", Map.of("name", notionStatus)));
        }

        @Test
        @DisplayName("DONE sets Complete=true (closure scenario, CA on cierre)")
        void done_marks_complete() {
            assertThat(map(snapshot().status("DONE").build()).get("Complete"))
                .isEqualTo(Map.of("checkbox", true));
        }

        @ParameterizedTest(name = "{0} → Complete=false")
        @CsvSource({"TODO", "IN_PROGRESS", "FAILED", "PLANNED", "WAITING"})
        @DisplayName("any non-DONE status sets Complete=false")
        void non_done_not_complete(String domainStatus) {
            assertThat(map(snapshot().status(domainStatus).build()).get("Complete"))
                .isEqualTo(Map.of("checkbox", false));
        }
    }

    @Nested
    @DisplayName("type → Type (select)")
    class Type {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "TASK, Task",
            "HABIT, Habit",
            "LEAD_MEASURE, Lead Measure",
            "ACTIVITY, Activity",
            "AGENDA, Agenda",
            "LEARNING_SESSION, Learning Session"
        })
        @DisplayName("maps every domain type to its Notion select option")
        void maps_type(String domainType, String notionType) {
            assertThat(selectName(map(snapshot().type(domainType).build()), "Type"))
                .isEqualTo(notionType);
        }

        @Test
        @DisplayName("AGENDA propagates to Notion (ADR-009 restriction is Apple-only)")
        void agenda_is_mapped_not_filtered() {
            assertThat(selectName(map(snapshot().type("AGENDA").build()), "Type"))
                .isEqualTo("Agenda");
        }
    }

    @Nested
    @DisplayName("start_time / end_time → Date (range, America/Bogota)")
    class Dates {

        @Test
        @DisplayName("maps start and end as an ISO datetime range in Bogota time")
        void maps_datetime_range() {
            Map<String, Object> date = dateValue(map(fullSnapshot()));

            assertThat(date.get("start")).isEqualTo("2026-07-06T09:30:00-05:00");
            assertThat(date.get("end")).isEqualTo("2026-07-06T11:00:00-05:00");
        }

        @Test
        @DisplayName("start without end produces a single-date value")
        void start_only_has_no_end() {
            Map<String, Object> date = dateValue(map(snapshot().startTime(START).build()));

            assertThat(date.get("start")).isEqualTo("2026-07-06T09:30:00-05:00");
            assertThat(date).doesNotContainKey("end");
        }

        @Test
        @DisplayName("local-midnight bounds degrade to date-only values (all-day)")
        void midnight_becomes_date_only() {
            Map<String, Object> date =
                dateValue(map(snapshot().startTime(BOGOTA_MIDNIGHT).build()));

            assertThat(date.get("start")).isEqualTo("2026-07-06");
        }

        @Test
        @DisplayName("clears Date explicitly when the executable has no times (full mirror, ADR-012 D3)")
        void clears_date_when_null() {
            assertThat(map(minimalSnapshot()).get("Date"))
                .isEqualTo(singletonMap("date", null));
        }
    }

    @Nested
    @DisplayName("scores → Priority Score / Urgence / Effort / Frequency (numbers)")
    class Scores {

        @Test
        @DisplayName("priority_score → Priority Score")
        void maps_priority_score() {
            assertThat(map(fullSnapshot()).get("Priority Score")).isEqualTo(Map.of("number", 0.8));
        }

        @Test
        @DisplayName("urgency_score → Urgence")
        void maps_urgency_score() {
            assertThat(map(fullSnapshot()).get("Urgence")).isEqualTo(Map.of("number", 0.5));
        }

        @Test
        @DisplayName("effort_score → Effort")
        void maps_effort_score() {
            assertThat(map(fullSnapshot()).get("Effort")).isEqualTo(Map.of("number", 3.0));
        }

        @Test
        @DisplayName("frequency → Frequency (ADR-012 D4)")
        void maps_frequency() {
            assertThat(map(fullSnapshot()).get("Frequency")).isEqualTo(Map.of("number", 2.0));
        }

        @Test
        @DisplayName("null scores clear their numbers explicitly (full mirror, ADR-012 D3)")
        void clears_null_scores() {
            Map<String, Object> props = map(minimalSnapshot());

            assertThat(props.get("Priority Score")).isEqualTo(singletonMap("number", null));
            assertThat(props.get("Urgence")).isEqualTo(singletonMap("number", null));
            assertThat(props.get("Effort")).isEqualTo(singletonMap("number", null));
            assertThat(props.get("Frequency")).isEqualTo(singletonMap("number", null));
        }
    }

    @Nested
    @DisplayName("is_important → Important (checkbox, ADR-012 D4)")
    class Important {

        @Test
        @DisplayName("true maps to a checked Important")
        void maps_important_true() {
            assertThat(map(fullSnapshot()).get("Important")).isEqualTo(Map.of("checkbox", true));
        }

        @Test
        @DisplayName("false and null map to an unchecked Important")
        void maps_important_false_and_null() {
            assertThat(map(snapshot().isImportant(false).build()).get("Important"))
                .isEqualTo(Map.of("checkbox", false));
            assertThat(map(snapshot().isImportant(null).build()).get("Important"))
                .isEqualTo(Map.of("checkbox", false));
        }
    }

    @Nested
    @DisplayName("impact → Impact (select, Spanish canonical options)")
    class Impact {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "1, Irrelevante",
            "2, Bajo",
            "3, Moderado",
            "4, Alto",
            "5, Crítico"
        })
        @DisplayName("maps the 1–5 scale to its option")
        void maps_impact(int impact, String option) {
            assertThat(selectName(map(snapshot().impact(impact).build()), "Impact"))
                .isEqualTo(option);
        }

        @Test
        @DisplayName("clamps the DDL upper bound (8) to Crítico")
        void clamps_overflow() {
            assertThat(selectName(map(snapshot().impact(8).build()), "Impact"))
                .isEqualTo("Crítico");
        }

        @Test
        @DisplayName("clears Impact explicitly when the profile has no value (full mirror)")
        void clears_null_impact() {
            assertThat(map(minimalSnapshot()).get("Impact"))
                .isEqualTo(singletonMap("select", null));
        }
    }

    @Nested
    @DisplayName("energy_drain → Energy (select, Spanish canonical options)")
    class Energy {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "1, Automático",
            "2, Ejecución",
            "3, Sostenido",
            "4, Exigente",
            "5, Intenso"
        })
        @DisplayName("maps the 1–5 scale to its option")
        void maps_energy(int energy, String option) {
            assertThat(selectName(map(snapshot().energyDrain(energy).build()), "Energy"))
                .isEqualTo(option);
        }

        @Test
        @DisplayName("clears Energy explicitly when the profile has no value (full mirror)")
        void clears_null_energy() {
            assertThat(map(minimalSnapshot()).get("Energy"))
                .isEqualTo(singletonMap("select", null));
        }
    }

    @Nested
    @DisplayName("mental_load → Mental Load (select, Spanish canonical options)")
    class MentalLoad {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "1, Rutinario",
            "2, Foco",
            "3, Análisis",
            "4, Complejo",
            "5, Abstracto"
        })
        @DisplayName("maps the 1–5 scale to its option")
        void maps_mental_load(int mentalLoad, String option) {
            assertThat(selectName(map(snapshot().mentalLoad(mentalLoad).build()), "Mental Load"))
                .isEqualTo(option);
        }

        @Test
        @DisplayName("clears Mental Load explicitly when the profile has no value (full mirror)")
        void clears_null_mental_load() {
            assertThat(map(minimalSnapshot()).get("Mental Load"))
                .isEqualTo(singletonMap("select", null));
        }
    }

    @Nested
    @DisplayName("cycle_id / parent_id → Cycle / Parent Task (relations)")
    class Relations {

        @Test
        @DisplayName("writes the Cycle relation with the resolved page id (CA-6)")
        void maps_cycle_relation() {
            Map<String, Object> props =
                NotionTaskMapper.map(fullSnapshot(), "cyclepage123", null);

            assertThat(props.get("Cycle")).isEqualTo(
                Map.of("relation", List.of(Map.of("id", "cyclepage123"))));
        }

        @Test
        @DisplayName("writes the Parent Task relation with the resolved page id")
        void maps_parent_relation() {
            Map<String, Object> props =
                NotionTaskMapper.map(fullSnapshot(), null, "parentpage456");

            assertThat(props.get("Parent Task")).isEqualTo(
                Map.of("relation", List.of(Map.of("id", "parentpage456"))));
        }

        @Test
        @DisplayName("clears relations explicitly when the external ids are unresolved (full mirror)")
        void clears_unresolved_relations() {
            Map<String, Object> props = map(fullSnapshot());

            assertThat(props.get("Cycle")).isEqualTo(Map.of("relation", List.of()));
            assertThat(props.get("Parent Task")).isEqualTo(Map.of("relation", List.of()));
        }
    }

    @Nested
    @DisplayName("read-only properties (CA-9) + canonical shape")
    class ReadOnly {

        @Test
        @DisplayName("the mapper never produces formula/rollup properties and always emits the full mirror")
        void never_emits_read_only_properties() {
            Map<String, Object> props =
                NotionTaskMapper.map(fullSnapshot(), "cycle1", "parent1");

            assertThat(props.keySet())
                .doesNotContainAnyElementsOf(NotionSchema.READ_ONLY_PROPERTIES)
                .containsOnly("Name", "Description", "Status", "Complete", "Type", "Date",
                    "Priority Score", "Urgence", "Effort", "Important", "Frequency",
                    "Impact", "Energy", "Mental Load", "Cycle", "Parent Task");
        }

        @Test
        @DisplayName("a minimal snapshot emits the same property set (canonical map, checksum-stable)")
        void minimal_snapshot_emits_same_property_set() {
            assertThat(map(minimalSnapshot()).keySet())
                .containsExactlyInAnyOrderElementsOf(
                    NotionTaskMapper.map(fullSnapshot(), "c", "p").keySet());
        }
    }
}
