package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hyperbrain.shared.outbox.OutboxWorker;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.NotionBackfillService;
import com.hyperbrain.sync.application.NotionTimeBlockRetentionService;
import com.hyperbrain.sync.application.SyncOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the outbound Time Blocks mirror (ADR-038): planner events drained from
 * the outbox against a stubbed Notion API. Covers the stable-identity replan (UPDATE, never a
 * duplicate CREATE — regression #15), the removal path, the settlement reflection, the
 * 400-on-archived-page degradation (no ERROR/retry loop), the retention sweep
 * (clear-relation → archive → unmap, idempotent) and its anti-resurrection guarantee, and the
 * one-shot backfill.
 */
@IntegrationTest
@DisplayName("Notion Time Blocks mirror — outbound write-back, sweep & backfill (ADR-038)")
class NotionTimeBlockMirrorIT {

    private static final String TB_DS = "af6834d49fe242d5a04dcf4b5f146b5c";
    private static final String TASKS_DS = "1bf8bc9c5d918171b7ea000b7e326082";
    private static final String CYCLES_DS = "1bf8bc9c5d9181e78737000b45812f45";

    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));

    private static final WireMockServer NOTION = new WireMockServer(
        WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void notionProperties(DynamicPropertyRegistry registry) {
        NOTION.start();
        registry.add("app.sync.notion.enabled", () -> "true");
        registry.add("app.sync.notion.base-url", NOTION::baseUrl);
        registry.add("app.sync.notion.token", () -> "test-token");
        registry.add("app.sync.notion.tasks-data-source-id", () -> TASKS_DS);
        registry.add("app.sync.notion.cycles-data-source-id", () -> CYCLES_DS);
        registry.add("app.sync.notion.timeblocks-data-source-id", () -> TB_DS);
        registry.add("app.sync.notion.min-request-interval-ms", () -> "0");
        registry.add("app.sync.notion.backoff-base-ms", () -> "10");
        registry.add("app.sync.notion.max-attempts", () -> "2");
        // Competing-listener discipline: this IT drives the pipeline in-process.
        registry.add("app.sync.consumer.enabled", () -> "false");
    }

    @AfterAll
    static void stopServer() {
        NOTION.stop();
    }

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotionTimeBlockRetentionService retentionService;
    @Autowired private NotionBackfillService backfillService;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM core_time_block_member");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        NOTION.resetAll();
    }

    @Test
    @DisplayName("planned day: an unmapped live block creates its page with composed title, Agenda and Tasks relation")
    void planned_day_creates_page() throws Exception {
        // Given a planner block whose member's task page is mapped
        UUID executableId = insertExecutable("Deep work task");
        insertTaskMapping(executableId, "aaaa0000000000000000000000000001");
        UUID blockId = insertBlock(START, START.plusMinutes(90), "PLANNED", "PLANNER", "Deep work");
        insertMember(blockId, executableId, 90, 0);
        insertPlannedDayEvent();
        String pageId = "0b48d06e-6773-44c0-a615-a6502bddea99";
        stubCreatePage(pageId);

        // When
        outboxWorker.drainBatch();

        // Then — one create with the canonical ADR-038 property set
        LoggedRequest request = singleRequest(postRequestedFor(urlEqualTo("/v1/pages"))
            .withRequestBody(matchingJsonPath("$.parent.data_source_id", WireMock.equalTo(TB_DS))));
        JsonNode props = objectMapper.readTree(request.getBodyAsString()).path("properties");
        assertThat(props.path("Name").path("title").get(0).path("text").path("content").asText())
            .isEqualTo("Mié 05 · 09:00–10:30 · Deep work");
        assertThat(props.path("Status").path("select").path("name").asText()).isEqualTo("Planned");
        assertThat(props.path("Origin").path("select").path("name").asText()).isEqualTo("Planner");
        assertThat(props.path("Planned Minutes").path("number").asInt()).isEqualTo(90);
        assertThat(props.path("Agenda").path("rich_text").get(0).path("text").path("content").asText())
            .isEqualTo("1. Deep work task — 90 min");
        assertThat(props.path("Tasks").path("relation").get(0).path("id").asText())
            .isEqualTo("aaaa0000000000000000000000000001");
        assertThat(props.has("Cycles")).isFalse();

        Map<String, Object> mapping = jdbcTemplate.queryForMap(
            "SELECT external_id, sync_status FROM sync_mappings "
                + "WHERE external_system = 'NOTION' AND local_id = ?", blockId);
        assertThat(mapping.get("external_id")).isEqualTo("0b48d06e677344c0a615a6502bddea99");
        assertThat(mapping.get("sync_status")).isEqualTo("SYNCED");
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("replan continuity (#15): a mapped block UPDATES its page in place — never a duplicate CREATE")
    void replan_updates_mapped_page() {
        // Given the block already mirrored under a stable id (ADR-027 D3)
        UUID executableId = insertExecutable("Deep work task");
        UUID blockId = insertBlock(START, START.plusMinutes(90), "PLANNED", "PLANNER", "Deep work");
        insertMember(blockId, executableId, 90, 0);
        String pageId = "b10c0000000000000000000000000010";
        insertBlockMapping(blockId, pageId);
        insertPlannedDayEvent();
        stubPatchPage(pageId);

        // When — the replan re-emits the day
        outboxWorker.drainBatch();

        // Then — PATCH on the existing page; zero creates
        singleRequest(patchRequestedFor(urlEqualTo("/v1/pages/" + pageId)));
        assertThat(NOTION.findAll(postRequestedFor(urlPathEqualTo("/v1/pages")))).isEmpty();
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("removed_block_ids: the dropped block's page is archived and its mapping removed")
    void removed_block_archives_page() {
        // Given a mapping whose block row a replan already deleted
        UUID removedBlockId = UUID.randomUUID();
        String pageId = "b10c0000000000000000000000000011";
        insertBlockMapping(removedBlockId, pageId);
        insertPlannedDayEvent(removedBlockId);
        stubPatchPage(pageId);

        // When
        outboxWorker.drainBatch();

        // Then
        singleRequest(patchRequestedFor(urlEqualTo("/v1/pages/" + pageId))
            .withRequestBody(matchingJsonPath("$.archived", WireMock.equalTo("true"))));
        assertThat(mappingCount(removedBlockId)).isZero();
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("settlement: TimeBlockSettledEvent patches Status=Settled and Actual Minutes onto the page")
    void settlement_reflects_status_and_actuals() {
        // Given a settled block still mapped
        UUID executableId = insertExecutable("Deep work task");
        UUID blockId = insertBlock(START, START.plusMinutes(90), "SETTLED", "PLANNER", "Deep work",
            85, START.plusHours(2));
        insertMember(blockId, executableId, 90, 0);
        String pageId = "b10c0000000000000000000000000012";
        insertBlockMapping(blockId, pageId);
        insertOutboxEvent("CORE_TIME_BLOCK", blockId.toString(), "TimeBlockSettledEvent", "{}",
            "SYSTEM");
        stubPatchPage(pageId);

        // When
        outboxWorker.drainBatch();

        // Then
        singleRequest(patchRequestedFor(urlEqualTo("/v1/pages/" + pageId))
            .withRequestBody(matchingJsonPath("$.properties.Status.select.name",
                WireMock.equalTo("Settled")))
            .withRequestBody(matchingJsonPath("$.properties['Actual Minutes'].number",
                WireMock.equalTo("85"))));
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("400-on-archived-page: the mirror skips (no recreate, no ERROR marker, no retry loop)")
    void archived_page_skips_without_loop() {
        // Given a live mapped block whose page the user archived in Notion (PATCH answers 400)
        UUID executableId = insertExecutable("Deep work task");
        UUID blockId = insertBlock(START, START.plusMinutes(90), "PLANNED", "PLANNER", "Deep work");
        insertMember(blockId, executableId, 90, 0);
        String pageId = "b10c0000000000000000000000000013";
        insertBlockMapping(blockId, pageId);
        insertPlannedDayEvent();
        NOTION.stubFor(WireMock.patch(urlEqualTo("/v1/pages/" + pageId))
            .willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"error\",\"status\":400,\"code\":\"validation_error\","
                    + "\"message\":\"Can't update a page that is archived.\"}")));

        // When — the drain must complete, not loop
        outboxWorker.drainBatch();

        // Then — event processed, no page created, mapping kept for the inbound de-scheduling
        assertThat(unprocessedEvents()).isZero();
        assertThat(NOTION.findAll(postRequestedFor(urlPathEqualTo("/v1/pages")))).isEmpty();
        Map<String, Object> mapping = jdbcTemplate.queryForMap(
            "SELECT sync_status FROM sync_mappings WHERE local_id = ?", blockId);
        assertThat(mapping.get("sync_status")).isEqualTo("SYNCED");
    }

    @Test
    @DisplayName("retention sweep: clear relation → archive → unmap, idempotent on a second pass")
    void sweep_is_idempotent() {
        // Given a terminal block settled beyond the retention window, still mapped
        UUID executableId = insertExecutable("Old work");
        UUID blockId = insertBlock(START.minusDays(45), START.minusDays(45).plusMinutes(60),
            "SETTLED", "PLANNER", null, 60, START.minusDays(41));
        insertMember(blockId, executableId, 60, 0);
        String pageId = "b10c0000000000000000000000000014";
        insertBlockMapping(blockId, pageId);
        stubPatchPage(pageId);

        // When
        int sweptFirst = retentionService.sweep(OffsetDateTime.now());
        int sweptSecond = retentionService.sweep(OffsetDateTime.now());

        // Then — first pass: relation cleared then archived; mapping gone; second pass: no-op
        assertThat(sweptFirst).isEqualTo(1);
        assertThat(sweptSecond).isZero();
        List<LoggedRequest> patches =
            NOTION.findAll(patchRequestedFor(urlEqualTo("/v1/pages/" + pageId)));
        assertThat(patches).hasSize(2);
        assertThat(patches.get(0).getBodyAsString()).contains("\"Tasks\"");
        assertThat(patches.get(1).getBodyAsString()).contains("\"archived\":true");
        assertThat(mappingCount(blockId)).isZero();
        // And PG history is untouched (the sweep never deletes domain rows)
        Integer blocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE id = ?", Integer.class, blockId);
        assertThat(blocks).isEqualTo(1);
    }

    @Test
    @DisplayName("anti-resurrection: a late event on a swept terminal block never recreates the page")
    void late_event_on_swept_block_does_not_resurrect() {
        // Given a swept terminal block (no mapping any more)
        UUID executableId = insertExecutable("Old work");
        UUID blockId = insertBlock(START.minusDays(45), START.minusDays(45).plusMinutes(60),
            "SETTLED", "PLANNER", null, 60, START.minusDays(41));
        insertMember(blockId, executableId, 60, 0);
        insertOutboxEvent("CORE_TIME_BLOCK", blockId.toString(), "TimeBlockSettledEvent", "{}",
            "SYSTEM");

        // When
        outboxWorker.drainBatch();

        // Then — the upsert's create-only-while-live guard suppresses the page
        assertThat(NOTION.findAll(postRequestedFor(urlPathEqualTo("/v1/pages")))).isEmpty();
        assertThat(mappingCount(blockId)).isZero();
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("backfill: mirrors live + recent SETTLED blocks once; EXPIRED and already-mapped are skipped")
    void backfill_is_idempotent() {
        // Given one live unmapped, one recent settled unmapped, one expired
        UUID liveExec = insertExecutable("Live work");
        UUID liveBlock = insertBlock(START, START.plusMinutes(60), "PLANNED", "PLANNER", null);
        insertMember(liveBlock, liveExec, 60, 0);
        UUID settledExec = insertExecutable("Settled work");
        UUID settledBlock = insertBlock(START.minusDays(2), START.minusDays(2).plusMinutes(60),
            "SETTLED", "PLANNER", null, 55, START.minusDays(2).plusHours(2));
        insertMember(settledBlock, settledExec, 60, 0);
        UUID expiredExec = insertExecutable("Expired work");
        UUID expiredBlock = insertBlock(START.minusDays(3), START.minusDays(3).plusMinutes(60),
            "EXPIRED", "PLANNER", null, null, START.minusDays(3).plusHours(2));
        insertMember(expiredBlock, expiredExec, 60, 0);
        // Each create must mint a distinct page id (sync_mappings uniqueness): scenario stub.
        NOTION.stubFor(post(urlPathEqualTo("/v1/pages"))
            .inScenario("backfill").whenScenarioStateIs(
                com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willSetStateTo("second")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"0b48d06e-6773-44c0-a615-a6502bddea01\"}")));
        NOTION.stubFor(post(urlPathEqualTo("/v1/pages"))
            .inScenario("backfill").whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"0b48d06e-6773-44c0-a615-a6502bddea02\"}")));
        stubPatchPage("0b48d06e677344c0a615a6502bddea01");
        stubPatchPage("0b48d06e677344c0a615a6502bddea02");

        // When
        Map<SyncOutcome, Integer> first = backfillService.backfillTimeBlocks();
        Map<SyncOutcome, Integer> second = backfillService.backfillTimeBlocks();

        // Then — two creates on the first pass; the second converges to updates
        assertThat(first.getOrDefault(SyncOutcome.CREATED, 0)).isEqualTo(2);
        assertThat(second.getOrDefault(SyncOutcome.CREATED, 0)).isZero();
        assertThat(second.getOrDefault(SyncOutcome.UPDATED, 0)).isEqualTo(2);
        assertThat(mappingCount(expiredBlock)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertExecutable(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status)
            VALUES (?, ?, ?, 'TASK', 'TODO')
            """, id, DataFixture.SYSTEM_USER_ID, name);
        return id;
    }

    private UUID insertBlock(OffsetDateTime start, OffsetDateTime end, String status,
                             String origin, String theme) {
        return insertBlock(start, end, status, origin, theme, null, null);
    }

    private UUID insertBlock(OffsetDateTime start, OffsetDateTime end, String status,
                             String origin, String theme, Integer actualMinutes,
                             OffsetDateTime settledAt) {
        UUID id = UUID.randomUUID();
        UUID anchor = jdbcTemplate.queryForObject(
            "SELECT id FROM core_executable LIMIT 1", UUID.class);
        jdbcTemplate.update("""
            INSERT INTO core_time_block
                (id, executable_id, date_start, date_end, status, origin, planned_minutes,
                 reason, theme, actual_duration_minutes, settled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'because', ?, ?, ?)
            """, id, anchor, start, end, status, origin,
            (int) java.time.Duration.between(start, end).toMinutes(), theme, actualMinutes,
            settledAt);
        return id;
    }

    private void insertMember(UUID blockId, UUID executableId, int minutes, int ord) {
        jdbcTemplate.update("""
            INSERT INTO core_time_block_member (block_id, executable_id, planned_minutes, ord)
            VALUES (?, ?, ?, ?)
            """, blockId, executableId, minutes, ord);
        // The reads fold the anchor into the member set; keep the anchor aligned with ord 0.
        if (ord == 0) {
            jdbcTemplate.update("UPDATE core_time_block SET executable_id = ? WHERE id = ?",
                executableId, blockId);
        }
    }

    private void insertBlockMapping(UUID blockId, String pageId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum,
                 sync_status, last_synced_at)
            VALUES (?, ?, ?, 'NOTION', ?, 'previous', 'SYNCED', now())
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, blockId,
            pageId.replace("-", ""));
    }

    private void insertTaskMapping(UUID executableId, String pageId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum,
                 sync_status, last_synced_at)
            VALUES (?, ?, ?, 'NOTION', ?, 'x', 'SYNCED', now())
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, executableId, pageId);
    }

    private void insertPlannedDayEvent(UUID... removedBlockIds) {
        StringBuilder removed = new StringBuilder("[");
        for (int index = 0; index < removedBlockIds.length; index++) {
            if (index > 0) {
                removed.append(',');
            }
            removed.append('"').append(removedBlockIds[index]).append('"');
        }
        removed.append(']');
        String payload = """
            {"user_id":"%s","target_day":"2026-08-05","zone_id":"America/Bogota",
             "energy_criterion":"Sleep Score 74","removed_block_ids":%s}
            """.formatted(DataFixture.SYSTEM_USER_ID, removed);
        insertOutboxEvent("AGENDA_BLOCK", DataFixture.SYSTEM_USER_ID.toString(),
            "AgendaBlockPlannedEvent", payload, "SYSTEM");
    }

    private void insertOutboxEvent(String aggregateType, String aggregateId, String eventType,
                                   String payload, String sourceSystem) {
        jdbcTemplate.update("""
            INSERT INTO outbox_events
                (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, now())
            """, UUID.randomUUID(), aggregateType, aggregateId, eventType, payload, sourceSystem);
    }

    private void stubCreatePage(String pageId) {
        NOTION.stubFor(post(urlPathEqualTo("/v1/pages"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"" + pageId + "\"}")));
    }

    private void stubPatchPage(String pageId) {
        NOTION.stubFor(WireMock.patch(urlEqualTo("/v1/pages/" + pageId))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"" + pageId + "\"}")));
    }

    private LoggedRequest singleRequest(
        com.github.tomakehurst.wiremock.matching.RequestPatternBuilder pattern) {
        List<LoggedRequest> requests = NOTION.findAll(pattern);
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private int mappingCount(UUID localId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE local_id = ?", Integer.class, localId);
        return count == null ? 0 : count;
    }

    private int unprocessedEvents() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE processed = false", Integer.class);
        return count == null ? 0 : count;
    }
}
