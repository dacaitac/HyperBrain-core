package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.application.AgendaPropuesta.Placement;
import com.hyperbrain.cognitive.application.AgendaPropuestaParser.AgendaPropuestaParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgendaPropuestaParser — tolerant reader over a strict schema (H3)")
class AgendaPropuestaParserTest {

    private static final UUID BLOCK = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AgendaPropuestaParser parser = new AgendaPropuestaParser(new ObjectMapper());

    @Test
    @DisplayName("parses a well-formed proposal with KEEP, MOVE and DROP decisions")
    void parses_well_formed_proposal() {
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T09:00:00Z","end":"2026-07-10T10:00:00Z","coach_note":"morning focus"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"},
              {"block_id":"33333333-3333-3333-3333-333333333333","placement":"DROP"}
            ]}""";

        AgendaPropuesta result = parser.parse(json);

        assertThat(result.decisions()).hasSize(3);
        assertThat(result.decisions().get(0).placement()).isEqualTo(Placement.MOVE);
        assertThat(result.decisions().get(0).start())
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(result.decisions().get(0).coachNote()).isEqualTo("morning focus");
        assertThat(result.decisions().get(1).placement()).isEqualTo(Placement.KEEP);
        assertThat(result.decisions().get(2).placement()).isEqualTo(Placement.DROP);
    }

    @Test
    @DisplayName("unwraps a Markdown-fenced JSON object (chat models fence output)")
    void unwraps_markdown_fence() {
        String fenced = "Here is the plan:\n```json\n"
            + "{\"decisions\":[{\"block_id\":\"" + BLOCK + "\",\"placement\":\"KEEP\"}]}\n```";

        AgendaPropuesta result = parser.parse(fenced);

        assertThat(result.decisions()).singleElement()
            .satisfies(d -> assertThat(d.blockId()).isEqualTo(BLOCK));
    }

    @Test
    @DisplayName("rejects text with no JSON object")
    void rejects_no_json() {
        assertThatThrownBy(() -> parser.parse("I cannot help with that."))
            .isInstanceOf(AgendaPropuestaParseException.class);
    }

    @Test
    @DisplayName("rejects malformed JSON")
    void rejects_malformed_json() {
        assertThatThrownBy(() -> parser.parse("{\"decisions\": [ {\"block_id\": }"))
            .isInstanceOf(AgendaPropuestaParseException.class);
    }

    @Test
    @DisplayName("rejects an unknown placement value")
    void rejects_unknown_placement() {
        String json = "{\"decisions\":[{\"block_id\":\"" + BLOCK + "\",\"placement\":\"SHUFFLE\"}]}";

        assertThatThrownBy(() -> parser.parse(json))
            .isInstanceOf(AgendaPropuestaParseException.class)
            .hasMessageContaining("placement");
    }

    @Test
    @DisplayName("rejects an unparseable block id")
    void rejects_bad_uuid() {
        String json = "{\"decisions\":[{\"block_id\":\"not-a-uuid\",\"placement\":\"KEEP\"}]}";

        assertThatThrownBy(() -> parser.parse(json))
            .isInstanceOf(AgendaPropuestaParseException.class)
            .hasMessageContaining("UUID");
    }

    @Test
    @DisplayName("rejects an unparseable timestamp")
    void rejects_bad_timestamp() {
        String json = "{\"decisions\":[{\"block_id\":\"" + BLOCK
            + "\",\"placement\":\"MOVE\",\"start\":\"9am\",\"end\":\"2026-07-10T10:00:00Z\"}]}";

        assertThatThrownBy(() -> parser.parse(json))
            .isInstanceOf(AgendaPropuestaParseException.class)
            .hasMessageContaining("timestamp");
    }

    @Test
    @DisplayName("rejects a MOVE decision missing its window")
    void rejects_move_without_window() {
        String json = "{\"decisions\":[{\"block_id\":\"" + BLOCK + "\",\"placement\":\"MOVE\"}]}";

        assertThatThrownBy(() -> parser.parse(json))
            .isInstanceOf(AgendaPropuestaParseException.class);
    }

    @Test
    @DisplayName("rejects a payload whose 'decisions' is not an array")
    void rejects_non_array_decisions() {
        assertThatThrownBy(() -> parser.parse("{\"decisions\":\"none\"}"))
            .isInstanceOf(AgendaPropuestaParseException.class)
            .hasMessageContaining("decisions");
    }
}
