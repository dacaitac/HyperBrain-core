package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionPageParser — raw page object → property view")
class NotionPageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotionPageParser parser = new NotionPageParser();

    @Test
    @DisplayName("parses a full Tasks page (API shape: plain_text, status, selects, date range, relations)")
    void parses_full_task_page() throws Exception {
        // Given a page as the Notion API returns it
        String json = """
            {
              "object": "page",
              "id": "2fa8bc9c-5d91-81ba-b3c9-f2a27fa48cc9",
              "last_edited_time": "2026-07-07T15:00:00.000Z",
              "archived": false,
              "in_trash": false,
              "parent": { "type": "data_source_id", "data_source_id": "1bf8bc9c-5d91-8171-b7ea-000b7e326082" },
              "properties": {
                "Name": { "type": "title", "title": [ { "plain_text": "Write tests" } ] },
                "Description": { "type": "rich_text", "rich_text": [ { "text": { "content": "Detailed description" } } ] },
                "Status": { "type": "status", "status": { "name": "In progress" } },
                "Complete": { "type": "checkbox", "checkbox": false },
                "Type": { "type": "select", "select": { "name": "Activity" } },
                "Date": { "type": "date", "date": { "start": "2026-07-07T10:00:00.000-05:00", "end": "2026-07-07T11:30:00.000-05:00" } },
                "Priority Score": { "type": "number", "number": 0.8 },
                "Urgence": { "type": "number", "number": 0.6 },
                "Effort": { "type": "number", "number": 2.5 },
                "Important": { "type": "checkbox", "checkbox": true },
                "Frequency": { "type": "number", "number": 3.0 },
                "Impact": { "type": "select", "select": { "name": "Alto" } },
                "Energy": { "type": "select", "select": { "name": "Intenso" } },
                "Mental Load": { "type": "select", "select": { "name": "Rutinario" } },
                "Cycle": { "type": "relation", "relation": [ { "id": "1bf8bc9c-5d91-81d8-82cf-e1f4aa38f295" } ] },
                "Parent Task": { "type": "relation", "relation": [] }
              }
            }
            """;

        // When
        NotionTaskPage page = parser.parseTask(objectMapper.readTree(json));

        // Then
        assertThat(page).usingRecursiveComparison().isEqualTo(new NotionTaskPage(
            "2fa8bc9c5d9181bab3c9f2a27fa48cc9",
            OffsetDateTime.parse("2026-07-07T15:00:00Z"),
            false,
            "Write tests", "Detailed description",
            "In progress", false, "Activity",
            "2026-07-07T10:00:00.000-05:00", "2026-07-07T11:30:00.000-05:00",
            0.8, 0.6, 2.5,
            true, 3.0,
            "Alto", "Intenso", "Rutinario",
            "1bf8bc9c5d9181d882cfe1f4aa38f295", null));
    }

    @Test
    @DisplayName("in_trash or archived flags mark the page for deletion (CA-7)")
    void flags_trashed_pages() throws Exception {
        String archived = "{\"id\":\"a\",\"archived\":true,\"properties\":{}}";
        String trashed = "{\"id\":\"b\",\"in_trash\":true,\"properties\":{}}";

        assertThat(parser.parseTask(objectMapper.readTree(archived)).archived()).isTrue();
        assertThat(parser.parseCycle(objectMapper.readTree(trashed)).archived()).isTrue();
    }

    @Test
    @DisplayName("missing properties map to null without failing")
    void tolerates_missing_properties() throws Exception {
        // Given a bare page (automation payloads may omit empty properties)
        NotionTaskPage page = parser.parseTask(objectMapper.readTree(
            "{\"id\":\"2fa8bc9c5d9181bab3c9f2a27fa48cc9\",\"properties\":{}}"));

        // Then
        assertThat(page.name()).isNull();
        assertThat(page.statusName()).isNull();
        assertThat(page.complete()).isNull();
        assertThat(page.priorityScore()).isNull();
        assertThat(page.important()).isNull();
        assertThat(page.frequency()).isNull();
        assertThat(page.dateStart()).isNull();
        assertThat(page.cycleRelationId()).isNull();
        assertThat(page.lastEditedTime()).isNull();
    }

    @Test
    @DisplayName("parses a Cycles page (Inactive checkbox, date-only range)")
    void parses_cycle_page() throws Exception {
        String json = """
            {
              "object": "page",
              "id": "1bf8bc9c-5d91-81d8-82cf-e1f4aa38f295",
              "last_edited_time": "2026-07-07T15:00:00.000Z",
              "properties": {
                "Name": { "type": "title", "title": [ { "plain_text": "Sprint 2" } ] },
                "Type": { "type": "select", "select": { "name": "MCI" } },
                "Date": { "type": "date", "date": { "start": "2026-07-01", "end": "2026-07-14" } },
                "Inactive": { "type": "checkbox", "checkbox": true }
              }
            }
            """;

        NotionCyclePage page = parser.parseCycle(objectMapper.readTree(json));

        assertThat(page).usingRecursiveComparison().isEqualTo(new NotionCyclePage(
            "1bf8bc9c5d9181d882cfe1f4aa38f295",
            OffsetDateTime.parse("2026-07-07T15:00:00Z"),
            false, "Sprint 2", "MCI", "2026-07-01", "2026-07-14", true));
    }

    @Test
    @DisplayName("multi-fragment rich text concatenates in order")
    void concatenates_text_fragments() throws Exception {
        String json = """
            {"id":"c","properties":{"Name":{"title":[
              {"plain_text":"Hyper"},{"plain_text":"Brain"}]}}}
            """;

        assertThat(parser.parseTask(objectMapper.readTree(json)).name()).isEqualTo("HyperBrain");
    }
}
