package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribute-by-attribute verification of the {@code core_executable} → Notion Tasks mapping
 * (HU-10 field mapping table, CA-10). Each nested block covers one domain attribute.
 */
@DisplayName("NotionTaskMapper — core_executable → Notion Tasks properties")
class NotionTaskMapperTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 2026-07-06 14:30 UTC = 09:30 in America/Bogota (UTC-5). */
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 7, 6, 14, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END =
        OffsetDateTime.of(2026, 7, 6, 16, 0, 0, 0, ZoneOffset.UTC);
    /** 05:00 UTC = local midnight in America/Bogota. */
    private static final OffsetDateTime BOGOTA_MIDNIGHT =
        OffsetDateTime.of(2026, 7, 6, 5, 0, 0, 0, ZoneOffset.UTC);

    private static ExecutableSnapshot snapshot() {
        return new ExecutableSnapshot(ID, USER_ID, null, null, "Write tests", "A description",
            "TASK", "TODO", 0.8, 0.5, 3.0, START, END, 3, 2, 4);
    }

    private static ExecutableSnapshot minimalSnapshot() {
        return new ExecutableSnapshot(ID, USER_ID, null, null, "Bare task", null,
            "TASK", "TODO", null, null, null, null, null, null, null, null);
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
            Map<String, Object> props = map(snapshot());

            assertThat(props.get("Name")).isEqualTo(
                Map.of("title", List.of(Map.of("text", Map.of("content", "Write tests")))));
        }

        @Test
        @DisplayName("truncates names beyond Notion's 2000-character limit")
        void truncates_long_name() {
            ExecutableSnapshot longName = new ExecutableSnapshot(ID, USER_ID, null, null,
                "x".repeat(2500), null, "TASK", "TODO",
                null, null, null, null, null, null, null, null);

            Map<String, Object> props = map(longName);

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
            Map<String, Object> props = map(snapshot());

            assertThat(props.get("Description")).isEqualTo(
                Map.of("rich_text", List.of(Map.of("text", Map.of("content", "A description")))));
        }

        @Test
        @DisplayName("omits Description when the domain value is null or blank")
        void omits_null_description() {
            assertThat(map(minimalSnapshot())).doesNotContainKey("Description");
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
            Map<String, Object> props = map(withStatus(domainStatus));

            assertThat(props.get("Status"))
                .isEqualTo(Map.of("status", Map.of("name", notionStatus)));
        }

        @Test
        @DisplayName("DONE sets Complete=true (closure scenario, CA on cierre)")
        void done_marks_complete() {
            assertThat(map(withStatus("DONE")).get("Complete"))
                .isEqualTo(Map.of("checkbox", true));
        }

        @ParameterizedTest(name = "{0} → Complete=false")
        @CsvSource({"TODO", "IN_PROGRESS", "FAILED", "PLANNED", "WAITING"})
        @DisplayName("any non-DONE status sets Complete=false")
        void non_done_not_complete(String domainStatus) {
            assertThat(map(withStatus(domainStatus)).get("Complete"))
                .isEqualTo(Map.of("checkbox", false));
        }

        private ExecutableSnapshot withStatus(String status) {
            return new ExecutableSnapshot(ID, USER_ID, null, null, "t", null, "TASK", status,
                null, null, null, null, null, null, null, null);
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
            ExecutableSnapshot typed = new ExecutableSnapshot(ID, USER_ID, null, null, "t", null,
                domainType, "TODO", null, null, null, null, null, null, null, null);

            assertThat(selectName(map(typed), "Type")).isEqualTo(notionType);
        }

        @Test
        @DisplayName("AGENDA propagates to Notion (ADR-009 restriction is Apple-only)")
        void agenda_is_mapped_not_filtered() {
            ExecutableSnapshot agenda = new ExecutableSnapshot(ID, USER_ID, null, null, "t", null,
                "AGENDA", "TODO", null, null, null, null, null, null, null, null);

            assertThat(selectName(map(agenda), "Type")).isEqualTo("Agenda");
        }
    }

    @Nested
    @DisplayName("start_time / end_time → Date (range, America/Bogota)")
    class Dates {

        @Test
        @DisplayName("maps start and end as an ISO datetime range in Bogota time")
        void maps_datetime_range() {
            Map<String, Object> date = dateValue(map(snapshot()));

            assertThat(date.get("start")).isEqualTo("2026-07-06T09:30:00-05:00");
            assertThat(date.get("end")).isEqualTo("2026-07-06T11:00:00-05:00");
        }

        @Test
        @DisplayName("start without end produces a single-date value")
        void start_only_has_no_end() {
            ExecutableSnapshot startOnly = new ExecutableSnapshot(ID, USER_ID, null, null, "t",
                null, "TASK", "TODO", null, null, null, START, null, null, null, null);

            Map<String, Object> date = dateValue(map(startOnly));

            assertThat(date.get("start")).isEqualTo("2026-07-06T09:30:00-05:00");
            assertThat(date).doesNotContainKey("end");
        }

        @Test
        @DisplayName("local-midnight bounds degrade to date-only values (all-day)")
        void midnight_becomes_date_only() {
            ExecutableSnapshot allDay = new ExecutableSnapshot(ID, USER_ID, null, null, "t",
                null, "TASK", "TODO", null, null, null, BOGOTA_MIDNIGHT, null, null, null, null);

            Map<String, Object> date = dateValue(map(allDay));

            assertThat(date.get("start")).isEqualTo("2026-07-06");
        }

        @Test
        @DisplayName("omits Date when the executable has no times")
        void omits_date_when_null() {
            assertThat(map(minimalSnapshot())).doesNotContainKey("Date");
        }
    }

    @Nested
    @DisplayName("scores → Priority Score / Urgence / Effort (numbers)")
    class Scores {

        @Test
        @DisplayName("priority_score → Priority Score")
        void maps_priority_score() {
            assertThat(map(snapshot()).get("Priority Score")).isEqualTo(Map.of("number", 0.8));
        }

        @Test
        @DisplayName("urgency_score → Urgence")
        void maps_urgency_score() {
            assertThat(map(snapshot()).get("Urgence")).isEqualTo(Map.of("number", 0.5));
        }

        @Test
        @DisplayName("effort_score → Effort")
        void maps_effort_score() {
            assertThat(map(snapshot()).get("Effort")).isEqualTo(Map.of("number", 3.0));
        }

        @Test
        @DisplayName("null scores are omitted so a PATCH never clears them")
        void omits_null_scores() {
            Map<String, Object> props = map(minimalSnapshot());

            assertThat(props).doesNotContainKeys("Priority Score", "Urgence", "Effort");
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
            assertThat(selectName(map(withProfile(null, null, impact)), "Impact"))
                .isEqualTo(option);
        }

        @Test
        @DisplayName("clamps the DDL upper bound (8) to Crítico")
        void clamps_overflow() {
            assertThat(selectName(map(withProfile(null, null, 8)), "Impact"))
                .isEqualTo("Crítico");
        }

        @Test
        @DisplayName("omits Impact when the profile has no value")
        void omits_null_impact() {
            assertThat(map(minimalSnapshot())).doesNotContainKey("Impact");
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
            assertThat(selectName(map(withProfile(energy, null, null)), "Energy"))
                .isEqualTo(option);
        }

        @Test
        @DisplayName("omits Energy when the profile has no value")
        void omits_null_energy() {
            assertThat(map(minimalSnapshot())).doesNotContainKey("Energy");
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
            assertThat(selectName(map(withProfile(null, mentalLoad, null)), "Mental Load"))
                .isEqualTo(option);
        }

        @Test
        @DisplayName("omits Mental Load when the profile has no value")
        void omits_null_mental_load() {
            assertThat(map(minimalSnapshot())).doesNotContainKey("Mental Load");
        }
    }

    @Nested
    @DisplayName("cycle_id / parent_id → Cycle / Parent Task (relations)")
    class Relations {

        @Test
        @DisplayName("writes the Cycle relation with the resolved page id (CA-6)")
        void maps_cycle_relation() {
            Map<String, Object> props =
                NotionTaskMapper.map(snapshot(), "cyclepage123", null);

            assertThat(props.get("Cycle")).isEqualTo(
                Map.of("relation", List.of(Map.of("id", "cyclepage123"))));
        }

        @Test
        @DisplayName("writes the Parent Task relation with the resolved page id")
        void maps_parent_relation() {
            Map<String, Object> props =
                NotionTaskMapper.map(snapshot(), null, "parentpage456");

            assertThat(props.get("Parent Task")).isEqualTo(
                Map.of("relation", List.of(Map.of("id", "parentpage456"))));
        }

        @Test
        @DisplayName("omits relations when the external ids are unresolved")
        void omits_unresolved_relations() {
            assertThat(map(snapshot())).doesNotContainKeys("Cycle", "Parent Task");
        }
    }

    @Nested
    @DisplayName("read-only properties (CA-9)")
    class ReadOnly {

        @Test
        @DisplayName("the mapper never produces formula/rollup properties")
        void never_emits_read_only_properties() {
            Map<String, Object> props =
                NotionTaskMapper.map(snapshot(), "cycle1", "parent1");

            assertThat(props.keySet())
                .doesNotContainAnyElementsOf(NotionSchema.READ_ONLY_PROPERTIES)
                .containsOnly("Name", "Description", "Status", "Complete", "Type", "Date",
                    "Priority Score", "Urgence", "Effort", "Impact", "Energy", "Mental Load",
                    "Cycle", "Parent Task");
        }
    }

    private static ExecutableSnapshot withProfile(Integer energy, Integer mental, Integer impact) {
        return new ExecutableSnapshot(ID, USER_ID, null, null, "t", null, "TASK", "TODO",
            null, null, null, null, null, energy, mental, impact);
    }
}
