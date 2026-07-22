package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.application.AgendaPropuesta.BlockDecision;
import com.hyperbrain.cognitive.application.AgendaPropuesta.Placement;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parses the LLM's raw completion text into a typed {@link AgendaPropuesta}. The model is asked for a
 * bare JSON object {@code {"decisions":[{"block_id","placement","start","end","coach_note"}]}}, but a
 * chat model may still wrap it in prose or a Markdown fence, so the parser first extracts the outermost
 * JSON object before reading it.
 *
 * <p><b>Fail-closed.</b> Any structural defect — no JSON object, malformed JSON, an unknown placement,
 * an unparseable id or timestamp, or a MOVE missing its window — throws
 * {@link AgendaPropuestaParseException}. The proposer turns that into a DEGRADED fallback (ADR-019):
 * the parser never guesses, so a malformed proposal can never reach the day.
 *
 * <p>Design pattern: tolerant reader over a strict schema — lenient about the envelope (fence/prose),
 * strict about the payload (types, enums, required fields).
 */
@Component
public class AgendaPropuestaParser {

    private final ObjectMapper objectMapper;

    public AgendaPropuestaParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses raw model text into an {@link AgendaPropuesta}.
     *
     * @param raw the model completion; never null
     * @return the parsed proposal
     * @throws AgendaPropuestaParseException when the text is not a well-formed, schema-valid proposal
     */
    public AgendaPropuesta parse(String raw) {
        JsonNode root = readObject(raw);
        JsonNode decisionsNode = root.get("decisions");
        if (decisionsNode == null || !decisionsNode.isArray()) {
            throw new AgendaPropuestaParseException("missing or non-array 'decisions'");
        }
        List<BlockDecision> decisions = new ArrayList<>();
        for (JsonNode node : decisionsNode) {
            decisions.add(readDecision(node));
        }
        return new AgendaPropuesta(decisions);
    }

    private JsonNode readObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AgendaPropuestaParseException("no JSON object found in completion");
        }
        try {
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AgendaPropuestaParseException("malformed JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    private BlockDecision readDecision(JsonNode node) {
        UUID blockId = readUuid(node, "block_id");
        Placement placement = readPlacement(node);
        OffsetDateTime start = readInstant(node, "start");
        OffsetDateTime end = readInstant(node, "end");
        String coachNote = node.hasNonNull("coach_note") ? node.get("coach_note").asText() : null;
        try {
            return new BlockDecision(blockId, placement, start, end, coachNote);
        } catch (IllegalArgumentException ex) {
            // A MOVE without a valid window is a schema breach, not a wall breach: fail the parse.
            throw new AgendaPropuestaParseException(ex.getMessage(), ex);
        }
    }

    private static UUID readUuid(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new AgendaPropuestaParseException("missing '" + field + "'");
        }
        try {
            return UUID.fromString(node.get(field).asText());
        } catch (IllegalArgumentException ex) {
            throw new AgendaPropuestaParseException("invalid UUID in '" + field + "': " + node.get(field).asText());
        }
    }

    private static Placement readPlacement(JsonNode node) {
        if (!node.hasNonNull("placement")) {
            throw new AgendaPropuestaParseException("missing 'placement'");
        }
        try {
            return Placement.valueOf(node.get("placement").asText().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AgendaPropuestaParseException("unknown placement: " + node.get("placement").asText());
        }
    }

    private static OffsetDateTime readInstant(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.get(field).asText());
        } catch (DateTimeParseException ex) {
            throw new AgendaPropuestaParseException("unparseable timestamp in '" + field + "': " + node.get(field).asText());
        }
    }

    /** Thrown when the model output is not a well-formed, schema-valid {@link AgendaPropuesta}. */
    public static class AgendaPropuestaParseException extends RuntimeException {
        public AgendaPropuestaParseException(String message) {
            super(message);
        }

        public AgendaPropuestaParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
