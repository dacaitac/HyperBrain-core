package com.hyperbrain.cognitive.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.application.ProposalTelemetry.DegradeReason;
import com.hyperbrain.cognitive.application.ProposalWallGuard.ProposalWall;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallBreach;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallGuardResult;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProposalTelemetry — raw-first pre-validation metric line (H3, ADR-016)")
class ProposalTelemetryTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProposalTelemetry telemetry = new ProposalTelemetry(objectMapper);
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.hyperbrain.cognitive.telemetry.proposal");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("intervened: emits the breach count and the per-wall tally")
    void intervened_emits_counts_and_reasons() throws Exception {
        WallGuardResult result = new WallGuardResult(List.of(
            new WallBreach(A, ProposalWall.SLEEP_FRONTIER),
            new WallBreach(B, ProposalWall.STRUCTURAL_IDENTITY)));

        telemetry.intervened(context(), result);

        JsonNode line = lastLine();
        assertThat(line.get("event").asText()).isEqualTo("llm_proposal");
        assertThat(line.get("outcome").asText()).isEqualTo("DEGRADED");
        assertThat(line.get("reason").asText()).isEqualTo(DegradeReason.WALL_BREACH.name());
        assertThat(line.get("candidate_count").asInt()).isEqualTo(2);
        assertThat(line.get("breached_blocks").asInt()).isEqualTo(2);
        assertThat(line.get("breaches_by_wall").get("SLEEP_FRONTIER").asInt()).isEqualTo(1);
        assertThat(line.get("breaches_by_wall").get("STRUCTURAL_IDENTITY").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("accepted: emits an ACCEPTED line with the candidate count")
    void accepted_emits_line() throws Exception {
        telemetry.accepted(context());

        JsonNode line = lastLine();
        assertThat(line.get("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(line.get("candidate_count").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("degraded: emits the reason and detail for a non-wall degradation")
    void degraded_emits_reason() throws Exception {
        telemetry.degraded(context(), DegradeReason.GATEWAY_FAILURE, "read timed out");

        JsonNode line = lastLine();
        assertThat(line.get("outcome").asText()).isEqualTo("DEGRADED");
        assertThat(line.get("reason").asText()).isEqualTo("GATEWAY_FAILURE");
        assertThat(line.get("detail").asText()).isEqualTo("read timed out");
    }

    private JsonNode lastLine() throws Exception {
        assertThat(appender.list).isNotEmpty();
        String message = appender.list.get(appender.list.size() - 1).getFormattedMessage();
        return objectMapper.readTree(message);
    }

    private static AgendaProposalContext context() {
        return new AgendaProposalContext(
            List.of(
                new AgendaBlock(A, WAKE, WAKE.plusMinutes(60), false, false, "r"),
                new AgendaBlock(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120), false, false, "r")),
            WAKE, BEDTIME, List.of(), Set.of(), 3, "NEUTRAL", Map.of());
    }
}
