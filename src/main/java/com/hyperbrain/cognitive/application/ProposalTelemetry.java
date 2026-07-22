package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.cognitive.application.ProposalWallGuard.ProposalWall;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallBreach;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallGuardResult;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Emits the H3 pre-validation telemetry: the LLM proposal's <b>intervention rate</b> — how often a
 * proposal is rejected by the bounded wall guard (and by which wall) before it can reach the day —
 * plus gateway and parse degradations. Following the raw-first pattern (H0 / ADR-016), each event is a
 * single structured JSON line so an external collector can compute rates without the core owning a
 * metrics store during the MVP. In H3 the numbers come from canned stub outputs; the real distribution
 * arrives in H4 (shadow) with a live model.
 *
 * <p>Emitted through a dedicated SLF4J logger (never {@code System.out}, per the logging rules); the
 * Logback console appender puts the line on stdout.
 */
@Component
public class ProposalTelemetry {

    private static final Logger log = LoggerFactory.getLogger("com.hyperbrain.cognitive.telemetry.proposal");

    private static final String EVENT = "llm_proposal";

    /** Why a proposal was degraded to the deterministic floor. */
    public enum DegradeReason {
        /** The gateway failed or timed out. */
        GATEWAY_FAILURE,
        /** The completion was not a well-formed, schema-valid proposal. */
        INVALID_JSON,
        /** The proposal breached one or more bounded hard walls. */
        WALL_BREACH,
        /** The proposal dropped a catastrophic fraction of the day's non-WIG blocks (gutted the day). */
        EXCESSIVE_DROP
    }

    private final ObjectMapper objectMapper;

    public ProposalTelemetry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Records a proposal accepted whole by the wall guard (no intervention).
     *
     * @param context the run's read model
     */
    public void accepted(AgendaProposalContext context) {
        ObjectNode line = base(context);
        line.put("outcome", "ACCEPTED");
        emit(line);
    }

    /**
     * Records a proposal degraded before the wall guard: a gateway failure or an invalid completion.
     *
     * @param context the run's read model
     * @param reason  the degradation cause (never {@code WALL_BREACH} here)
     * @param detail  a short, non-sensitive cause description
     */
    public void degraded(AgendaProposalContext context, DegradeReason reason, String detail) {
        ObjectNode line = base(context);
        line.put("outcome", "DEGRADED");
        line.put("reason", reason.name());
        line.put("detail", detail == null ? "" : detail);
        emit(line);
    }

    /**
     * Records a proposal the wall guard rejected: the intervention count and the breach tally per wall,
     * the core H3 measurement.
     *
     * @param context the run's read model
     * @param result  the wall-guard breaches (must be non-empty)
     */
    public void intervened(AgendaProposalContext context, WallGuardResult result) {
        ObjectNode line = base(context);
        line.put("outcome", "DEGRADED");
        line.put("reason", DegradeReason.WALL_BREACH.name());
        line.put("breached_blocks", result.breaches().size());

        Map<ProposalWall, Integer> perWall = new EnumMap<>(ProposalWall.class);
        for (WallBreach breach : result.breaches()) {
            perWall.merge(breach.wall(), 1, Integer::sum);
        }
        ObjectNode byWall = line.putObject("breaches_by_wall");
        perWall.forEach((wall, count) -> byWall.put(wall.name(), count));

        ArrayNode breaches = line.putArray("breaches");
        for (WallBreach breach : result.breaches()) {
            ObjectNode node = breaches.addObject();
            node.put("block_id", breach.blockId().toString());
            node.put("wall", breach.wall().name());
        }
        emit(line);
    }

    private ObjectNode base(AgendaProposalContext context) {
        ObjectNode line = objectMapper.createObjectNode();
        line.put("event", EVENT);
        line.put("candidate_count", context.candidateBlocks().size());
        return line;
    }

    private void emit(ObjectNode line) {
        try {
            log.info("{}", objectMapper.writeValueAsString(line));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize proposal telemetry line: {}", ex.getOriginalMessage());
        }
    }
}
