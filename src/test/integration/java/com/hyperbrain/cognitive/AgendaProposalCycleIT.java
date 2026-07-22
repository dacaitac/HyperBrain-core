package com.hyperbrain.cognitive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.planner.application.AgendaGenerationService;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end of the propose-then-validate cycle (HU-01c H3) against a real PostgreSQL, driven by a
 * deterministic {@link LlmGateway} stub (no real model). The feature flag is on for this context only;
 * the stub returns canned proposals so the seam — {@code floor → propose → wall guard → materialize},
 * and the DEGRADED fallback — is exercised whole. Zero real LLM traffic.
 */
@IntegrationTest
@TestPropertySource(properties = "app.cognitive.llm-propose.enabled=true")
@Import(AgendaProposalCycleIT.StubGatewayConfig.class)
@DisplayName("AgendaProposalCycle — propose → validate → materialize / DEGRADED (H3, stub gateway)")
class AgendaProposalCycleIT {

    private static final java.util.UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, UTC);
    private static final String COACH_NOTE = "coached by llm";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgendaGenerationService service;
    @Autowired private StubGateway gateway;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        jdbcTemplate.update("UPDATE sys_user SET settings = '{}'::jsonb WHERE id = ?", USER);
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
        gateway.reset();
    }

    @Test
    @DisplayName("a valid LLM proposal is materialized as-is (coach note lands on the block reason)")
    void valid_proposal_materializes() {
        insertTask("High", 0.9, 60);
        insertTask("Low", 0.3, 60);
        // Stub: keep every candidate id the prompt lists, attaching the coach note — proving the LLM
        // path ran (the floor would have written its own placement reason instead).
        gateway.respondWith(prompt -> keepAll(prompt, COACH_NOTE));

        service.generate(USER, DAY, UTC, NOON, false);

        List<String> reasons = jdbcTemplate.queryForList(
            "SELECT reason FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED'",
            String.class);
        assertThat(reasons).hasSize(2).allMatch(COACH_NOTE::equals);
    }

    @Test
    @DisplayName("an invalid LLM completion degrades to the humanized floor — the day still materializes")
    void invalid_completion_degrades_to_floor() {
        insertTask("High", 0.9, 60);
        insertTask("Low", 0.3, 60);
        gateway.respondWith(prompt -> "I'm sorry, I can't do that.");

        service.generate(USER, DAY, UTC, NOON, false);

        List<String> reasons = jdbcTemplate.queryForList(
            "SELECT reason FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED'",
            String.class);
        // The floor still planned both tasks; none carries the (never-applied) coach note.
        assertThat(reasons).hasSize(2).noneMatch(COACH_NOTE::equals);
    }

    @Test
    @DisplayName("a proposal that drops the WIG degrades — the WIG lead measure keeps its block")
    void wig_drop_degrades_to_floor() {
        java.util.UUID mci = insertCycle();
        java.util.UUID wig = insertLeadMeasure("Predictive action", mci, 0.5);
        insertTask("Whirlwind", 0.99, 60);
        gateway.respondWith(prompt -> dropAll(prompt)); // dropping the WIG breaches WIG_PROTECTED

        service.generate(USER, DAY, UTC, NOON, false);

        Integer wigBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE executable_id = ?", Integer.class, wig);
        assertThat(wigBlocks).isEqualTo(1); // DEGRADED to the floor, which reserves the WIG
    }

    @Test
    @DisplayName("bypass: an accepted LLM arrangement with overlapping blocks materializes as-is "
        + "(the floor validator never re-shuffles it)")
    void accepted_llm_overlap_materializes_as_is() {
        insertTask("First", 0.9, 60);
        insertTask("Second", 0.3, 60);
        // The LLM moves both blocks into overlapping windows (09:00–10:00 and 09:30–10:30). Block-vs-block
        // overlap is the LLM's arrangement authority — not a bounded wall — so the guard accepts it and
        // the floor's AgendaValidator is bypassed. The deterministic floor would instead strip the second.
        gateway.respondWith(this::moveOverlapping);

        service.generate(USER, DAY, UTC, NOON, false);

        List<OffsetDateTime> starts = jdbcTemplate.queryForList(
            "SELECT date_start FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED' "
                + "ORDER BY date_start", OffsetDateTime.class);
        List<OffsetDateTime> ends = jdbcTemplate.queryForList(
            "SELECT date_end FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED' "
                + "ORDER BY date_start", OffsetDateTime.class);
        // Both blocks survive at their overlapping windows — nothing was stripped.
        assertThat(starts).containsExactly(
            OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, UTC));
        assertThat(starts.get(1)).isBefore(ends.get(0)); // they genuinely overlap
    }

    @Test
    @DisplayName("DEGRADED path runs the floor validator: an invalid completion yields the floor's "
        + "non-overlapping, wall-respecting blocks")
    void degraded_path_runs_floor_validator() {
        // A read-only AGENDA wall 07:00–09:00; a task the floor must place around it. On DEGRADED (invalid
        // completion) the day is the humanized floor, which passes through the AgendaValidator — so the
        // block never lands on the wall.
        insertAgendaEvent(OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        java.util.UUID task = insertTask("Work", 0.9, 60);
        gateway.respondWith(prompt -> "not json at all");

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block WHERE executable_id = ?", OffsetDateTime.class, task);
        assertThat(blockStart).isAfterOrEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
    }

    // ─── stub proposal builders (read the run's block ids from the prompt's control data) ─────────

    private String moveOverlapping(LlmPrompt prompt) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode decisions = root.putArray("decisions");
        List<java.util.UUID> ids = candidateIds(prompt, mapper);
        for (int i = 0; i < ids.size(); i++) {
            OffsetDateTime start = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC).plusMinutes(30L * i);
            ObjectNode decision = decisions.addObject();
            decision.put("block_id", ids.get(i).toString());
            decision.put("placement", "MOVE");
            decision.put("start", start.toString());
            decision.put("end", start.plusMinutes(60).toString());
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    private String keepAll(LlmPrompt prompt, String note) {
        return proposal(prompt, "KEEP", note);
    }

    private String dropAll(LlmPrompt prompt) {
        return proposal(prompt, "DROP", null);
    }

    private String proposal(LlmPrompt prompt, String placement, String note) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode decisions = root.putArray("decisions");
        for (java.util.UUID blockId : candidateIds(prompt, mapper)) {
            ObjectNode decision = decisions.addObject();
            decision.put("block_id", blockId.toString());
            decision.put("placement", placement);
            if (note != null) {
                decision.put("coach_note", note);
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<java.util.UUID> candidateIds(LlmPrompt prompt, ObjectMapper mapper) {
        try {
            String user = prompt.user();
            String controlData = user.substring(user.indexOf('{'), user.lastIndexOf('}') + 1);
            JsonNode root = mapper.readTree(controlData);
            List<java.util.UUID> ids = new java.util.ArrayList<>();
            for (JsonNode block : root.get("candidate_blocks")) {
                ids.add(java.util.UUID.fromString(block.get("block_id").asText()));
            }
            return ids;
        } catch (Exception ex) {
            throw new IllegalStateException("stub could not read candidate ids from the prompt", ex);
        }
    }

    // ─── fixtures ────────────────────────────────────────────────────────────────

    private java.util.UUID insertTask(String name, double priority, int estimatedMinutes) {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, priority);
        jdbcTemplate.update(
            "INSERT INTO core_execution_profile (executable_id, estimated_minutes) VALUES (?, ?)",
            id, estimatedMinutes);
        return id;
    }

    private void insertAgendaEvent(OffsetDateTime start, OffsetDateTime end) {
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, 'Meeting', 'AGENDA', 'TODO', ?, ?)
            """, java.util.UUID.randomUUID(), USER, start, end);
    }

    private java.util.UUID insertCycle() {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, start_date, end_date)
            VALUES (?, ?, 'MCI', 'MCI', 'ACTIVE', ?, ?)
            """, id, USER, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        return id;
    }

    private java.util.UUID insertLeadMeasure(String name, java.util.UUID cycleId, double priority) {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status, priority_score)
            VALUES (?, ?, ?, ?, 'LEAD_MEASURE', 'TODO', ?)
            """, id, USER, cycleId, name, priority);
        jdbcTemplate.update(
            "INSERT INTO core_execution_profile (executable_id, estimated_minutes) VALUES (?, 30)", id);
        return id;
    }

    /** A controllable in-process stand-in for the real LLM: no network, fully deterministic. */
    static class StubGateway implements LlmGateway {
        private volatile Function<LlmPrompt, String> responder = prompt -> "{\"decisions\":[]}";

        void respondWith(Function<LlmPrompt, String> responder) {
            this.responder = responder;
        }

        void reset() {
            this.responder = prompt -> "{\"decisions\":[]}";
        }

        @Override
        public String complete(LlmPrompt prompt) {
            return responder.apply(prompt);
        }
    }

    @TestConfiguration
    static class StubGatewayConfig {
        @Bean
        StubGateway llmGateway() {
            return new StubGateway();
        }
    }
}
