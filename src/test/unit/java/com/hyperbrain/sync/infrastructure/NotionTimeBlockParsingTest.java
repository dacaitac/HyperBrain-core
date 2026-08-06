package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.NotionTimeBlockPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing tests of the Time Blocks page view (ADR-038), including the {@code has_more}
 * truncation flag and the Tasks-mapper tolerance of the new synced property: the dual
 * relation's echo fires webhooks on Task pages carrying a "Time Blocks" property, which the
 * Tasks parser must ignore without disturbing any parsed field.
 */
@DisplayName("NotionPageParser — Time Blocks page + Tasks synced-property tolerance (ADR-038)")
class NotionTimeBlockParsingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotionPageParser parser = new NotionPageParser();

    @Test
    @DisplayName("parses a Time Blocks page: title, date bounds, selects, numbers, relation and sync note")
    void parses_time_block_page() throws JsonProcessingException {
        String json = """
            {"object":"page","id":"0b48d06e-6773-44c0-a615-a6502bddea54",
             "last_edited_time":"2026-08-05T14:30:00.000Z","archived":false,
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"Mié 05 · 09:00–10:30 · Deep work"}]},
               "Date":{"type":"date","date":{"start":"2026-08-05T09:00:00.000-05:00","end":"2026-08-05T10:30:00.000-05:00"}},
               "Status":{"type":"select","select":{"name":"Planned"}},
               "Origin":{"type":"select","select":{"name":"Planner"}},
               "Planned Minutes":{"type":"number","number":90},
               "Actual Minutes":{"type":"number","number":null},
               "Sync Note":{"type":"rich_text","rich_text":[{"plain_text":"note"}]},
               "Tasks":{"type":"relation","relation":[{"id":"aaaa0000-0000-0000-0000-000000000001"},
                                                       {"id":"bbbb0000-0000-0000-0000-000000000002"}],
                        "has_more":false}}}
            """;

        NotionTimeBlockPage page = parser.parseTimeBlock(objectMapper.readTree(json));

        assertThat(page.pageId()).isEqualTo("0b48d06e677344c0a615a6502bddea54");
        assertThat(page.title()).isEqualTo("Mié 05 · 09:00–10:30 · Deep work");
        assertThat(page.dateStart()).isEqualTo("2026-08-05T09:00:00.000-05:00");
        assertThat(page.dateEnd()).isEqualTo("2026-08-05T10:30:00.000-05:00");
        assertThat(page.statusName()).isEqualTo("Planned");
        assertThat(page.originName()).isEqualTo("Planner");
        assertThat(page.plannedMinutes()).isEqualTo(90.0);
        assertThat(page.actualMinutes()).isNull();
        assertThat(page.taskRelationIds()).containsExactly(
            "aaaa0000000000000000000000000001", "bbbb0000000000000000000000000002");
        assertThat(page.taskRelationHasMore()).isFalse();
        assertThat(page.syncNote()).isEqualTo("note");
        assertThat(page.archived()).isFalse();
    }

    @Test
    @DisplayName("has_more guard: a truncated Tasks relation surfaces the flag (membership must not be applied)")
    void truncated_relation_surfaces_has_more() throws JsonProcessingException {
        String json = """
            {"object":"page","id":"0b48d06e-6773-44c0-a615-a6502bddea54","archived":false,
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"t"}]},
               "Tasks":{"type":"relation","relation":[{"id":"aaaa0000-0000-0000-0000-000000000001"}],
                        "has_more":true}}}
            """;

        NotionTimeBlockPage page = parser.parseTimeBlock(objectMapper.readTree(json));

        assertThat(page.taskRelationHasMore()).isTrue();
    }

    @Test
    @DisplayName("Tasks-mapper tolerance: a Task page carrying the synced \"Time Blocks\" relation parses unchanged")
    void task_page_tolerates_time_blocks_property() throws JsonProcessingException {
        String json = """
            {"object":"page","id":"2fa8bc9c-5d91-81ba-b3c9-f2a27fa48cc9",
             "last_edited_time":"2026-08-05T14:30:00.000Z","archived":false,
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"Plan sprint"}]},
               "Status":{"type":"status","status":{"name":"Not started"}},
               "Complete":{"type":"checkbox","checkbox":false},
               "Type":{"type":"select","select":{"name":"Task"}},
               "Time Blocks":{"type":"relation","relation":[{"id":"0b48d06e-6773-44c0-a615-a6502bddea54"}],
                              "has_more":false}}}
            """;

        NotionTaskPage page = parser.parseTask(objectMapper.readTree(json));

        // The synced property is deliberately ignored: every parsed field is exactly as without it.
        assertThat(page.name()).isEqualTo("Plan sprint");
        assertThat(page.statusName()).isEqualTo("Not started");
        assertThat(page.complete()).isFalse();
        assertThat(page.typeName()).isEqualTo("Task");
        assertThat(page.cycleRelationId()).isNull();
        assertThat(page.parentRelationId()).isNull();
    }
}
