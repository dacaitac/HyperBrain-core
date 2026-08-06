package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.NotionTimeBlockMirrorService;
import com.hyperbrain.sync.application.SyncEventIngestionService;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import com.hyperbrain.sync.infrastructure.NotionEnvelopeNormalizer;
import com.hyperbrain.sync.infrastructure.SqsConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the Time Blocks inbound (ADR-038): real Notion webhook envelopes pushed
 * through the consumer pipeline (normalizer kill-switch → dedup → routing →
 * {@code NotionTimeBlockSyncService} → {@code PlannerTimeBlockPort}) against a real PostgreSQL.
 * Covers the committee's mandatory scenarios: the USER-origin flip that survives a replan, the
 * absolute-no-op anti-echo guard, the deterministic D5 move with the emptied-source deletion and
 * the due-date re-projection on additions and removals, WIG atomicity as origin and destination,
 * the terminal read-only wall, the {@code has_more} truncation guard, the stable block identity
 * (#15) and the relation-only creation.
 */
@IntegrationTest
@DisplayName("Notion Time Blocks inbound — authority matrix over the planner port (ADR-038)")
class NotionTimeBlockInboundIT {

    private static final String TB_DS = "af6834d49fe242d5a04dcf4b5f146b5c";
    private static final String TB_DB = "0b48d06e677344c0a615a6502bddea54";
    private static final String TASKS_DS = "1bf8bc9c5d918171b7ea000b7e326082";
    private static final String CYCLES_DS = "1bf8bc9c5d9181e78737000b45812f45";

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 5);
    private static final OffsetDateTime NINE =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final String PAST_SYNC = "2026-01-01T00:00:00Z";
    private static final String EDITED_NOW = "2026-08-05T20:00:00.000Z";

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
        registry.add("app.sync.notion.timeblocks-database-id", () -> TB_DB);
        registry.add("app.sync.notion.timeblocks-inbound-enabled", () -> "true");
        registry.add("app.sync.notion.min-request-interval-ms", () -> "0");
        // Competing-listener discipline: envelopes are pushed in-process, not through SQS.
        registry.add("app.sync.consumer.enabled", () -> "false");
    }

    @AfterAll
    static void stopServer() {
        NOTION.stop();
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.hyperbrain.shared.outbox.OutboxWorker outboxWorker;
    @Autowired private SyncEventIngestionService ingestionService;
    @Autowired private NotionEnvelopeNormalizer normalizer;
    @Autowired private PlannerTimeBlockPort blockPort;
    @Autowired private NotionTimeBlockMirrorService mirrorService;
    @Autowired private PlannerStateRepository plannerStateRepository;

    private SqsConsumer consumer;

    @BeforeEach
    void cleanState() throws Exception {
        consumer = new SqsConsumer(objectMapper, ingestionService, normalizer);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM processed_message");
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
    @DisplayName("retime flips origin=USER, and the replan neither destroys nor retimes the USER block")
    void retime_flips_origin_and_survives_replan() {
        // Given a mapped PLANNER block 09:00–10:30
        UUID taskId = insertExecutable("Deep work task", "TASK");
        String taskPage = insertTaskMapping(taskId);
        UUID blockId = insertBlock(NINE, NINE.plusMinutes(90), "PLANNER", "Deep work");
        insertMember(blockId, taskId, 90, 0);
        String pageId = newPageId();
        insertBlockMapping(blockId, pageId, "stale-checksum");

        // When the user retimes it in Notion to 13:00–14:30 (theme untouched)
        deliver(timeBlockPage(pageId, "Mié 05 · 13:00–14:30 · Deep work",
            "2026-08-05T13:00:00.000-05:00", "2026-08-05T14:30:00.000-05:00", false,
            taskPage));

        // Then the block is retimed and flipped to USER
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT origin, date_start, date_end FROM core_time_block WHERE id = ?", blockId);
        assertThat(row.get("origin")).isEqualTo("USER");
        assertThat(instant(row.get("date_start")))
            .isEqualTo(Instant.parse("2026-08-05T18:00:00Z"));
        // And the Apple side is notified while the Notion echo is loop-protected
        assertThat(countEvents("TimeBlockChangedEvent", "NOTION")).isEqualTo(1);
        assertThat(countEvents("TimeBlockChangedEvent", "SYSTEM")).isEqualTo(1);

        // And a full replan (empty desired plan) removes PLANNER blocks but never the USER block
        UUID plannerBlock = insertBlock(NINE.plusHours(6), NINE.plusHours(7), "PLANNER", null);
        UUID otherTask = insertExecutable("Other", "TASK");
        insertMember(plannerBlock, otherTask, 60, 0);
        List<UUID> removed = plannerStateRepository.reconcilePlannedBlocks(
            DataFixture.SYSTEM_USER_ID, DAY, BOGOTA, List.of());
        assertThat(removed).containsExactly(plannerBlock);
        Map<String, Object> survivor = jdbcTemplate.queryForMap(
            "SELECT origin, date_start FROM core_time_block WHERE id = ?", blockId);
        assertThat(survivor.get("origin")).isEqualTo("USER");
        assertThat(instant(survivor.get("date_start")))
            .isEqualTo(Instant.parse("2026-08-05T18:00:00Z"));
    }

    @Test
    @DisplayName("anti-echo guard: a delivery matching the canonical state is an absolute no-op — origin never flips")
    void echo_never_flips_origin() {
        // Given the mapping stores the checksum of the row's canonical projection (a mirror echo)
        UUID taskId = insertExecutable("Deep work task", "TASK");
        String taskPage = insertTaskMapping(taskId);
        UUID blockId = insertBlock(NINE, NINE.plusMinutes(90), "PLANNER", "Deep work");
        insertMember(blockId, taskId, 90, 0);
        String pageId = newPageId();
        TimeBlockSnapshot snapshot = blockPort.findBlock(blockId).orElseThrow();
        String canonicalChecksum = mirrorService.checksum(pageId,
            mirrorService.canonicalProps(snapshot, null));
        insertBlockMapping(blockId, pageId, canonicalChecksum);

        // When the webhook echo of the mirror's own write arrives
        deliver(timeBlockPage(pageId, "Mié 05 · 09:00–10:30 · Deep work",
            "2026-08-05T09:00:00.000-05:00", "2026-08-05T10:30:00.000-05:00", false, taskPage));

        // Then — absolutely nothing: no flip, no outbox traffic, no reconciliation freeze
        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_time_block WHERE id = ?", String.class, blockId))
            .isEqualTo("PLANNER");
        assertThat(totalOutboxEvents()).isZero();
    }

    @Test
    @DisplayName("regression core#57 ghost state: a retime applies even when the checksum matches the mirror's own write")
    void retime_applies_when_checksum_matches_mirror_write() {
        // Given a mapped USER block whose mapping stores the checksum of the mirror's last write
        // (the exact prod state after a Notion-born block's re-mirror)
        UUID taskId = insertExecutable("Deep work task", "TASK");
        String taskPage = insertTaskMapping(taskId);
        UUID blockId = insertBlock(NINE, NINE.plusMinutes(60), "USER", "Deep work");
        insertMember(blockId, taskId, 60, 0);
        String pageId = newPageId();
        TimeBlockSnapshot snapshot = blockPort.findBlock(blockId).orElseThrow();
        insertBlockMapping(blockId, pageId,
            mirrorService.checksum(pageId, mirrorService.canonicalProps(snapshot, null)));

        // When the user retimes the page 09:00–10:00 → 10:30–11:30 (Notion millisecond format)
        deliver(timeBlockPage(pageId, "Mié 05 · 09:00–10:00 · Deep work",
            "2026-08-05T10:30:00.000-05:00", "2026-08-05T11:30:00.000-05:00", false, taskPage));

        // Then — PG takes the new window (never a silent echo-discard) and both satellites follow
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT origin, date_start, date_end FROM core_time_block WHERE id = ?", blockId);
        assertThat(instant(row.get("date_start")))
            .isEqualTo(Instant.parse("2026-08-05T15:30:00Z"));
        assertThat(instant(row.get("date_end")))
            .isEqualTo(Instant.parse("2026-08-05T16:30:00Z"));
        assertThat(row.get("origin")).isEqualTo("USER");
        assertThat(countEvents("TimeBlockChangedEvent", "NOTION")).isEqualTo(1);
        assertThat(countEvents("TimeBlockChangedEvent", "SYSTEM")).isEqualTo(1);
    }

    @Test
    @DisplayName("D5 move: an added member leaves its previous block; the emptied source is deleted and propagated; due dates re-project")
    void d5_move_deletes_emptied_source_and_reprojects_due_dates() {
        // Given block A(X) and block B(Y), same day, both mirrored
        UUID taskX = insertExecutable("Task X", "TASK");
        UUID taskY = insertExecutable("Task Y", "TASK");
        String pageX = insertTaskMapping(taskX);
        String pageY = insertTaskMapping(taskY);
        UUID blockA = insertBlock(NINE, NINE.plusMinutes(60), "PLANNER", null);
        insertMember(blockA, taskX, 60, 0);
        UUID blockB = insertBlock(NINE.plusHours(3), NINE.plusHours(3).plusMinutes(90), "PLANNER",
            "Afternoon");
        insertMember(blockB, taskY, 90, 0);
        insertBlockMapping(blockA, newPageId(), "a");
        String pageB = newPageId();
        insertBlockMapping(blockB, pageB, "stale");

        // When the user adds X to B's Tasks relation in Notion
        deliver(timeBlockPage(pageB, "Mié 05 · 12:00–13:30 · Afternoon",
            "2026-08-05T12:00:00.000-05:00", "2026-08-05T13:30:00.000-05:00", false,
            pageY, pageX));

        // Then X moved into B (ord = max + 1) and B flipped to USER under its stable id (#15)
        Map<String, Object> moved = jdbcTemplate.queryForMap(
            "SELECT ord FROM core_time_block_member WHERE block_id = ? AND executable_id = ?",
            blockB, taskX);
        assertThat(moved.get("ord")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_time_block WHERE id = ?", String.class, blockB))
            .isEqualTo("USER");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT external_id FROM sync_mappings WHERE local_id = ?", String.class, blockB))
            .isEqualTo(pageB);
        // The emptied source block A is gone and its removal staged for both satellites
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE id = ?", Integer.class, blockA)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'TimeBlockChangedEvent' "
                + "AND aggregate_id = ? AND payload::text LIKE '%DELETED%'",
            Integer.class, blockA.toString())).isEqualTo(1);
        // The ADR-035 D-C hook fired for the added member (alta)
        assertThat(memberRefreshEvents(taskX)).isEqualTo(1);

        // And when the user later removes X from B (baja) the hook fires again
        deliver(timeBlockPage(pageB, "Mié 05 · 12:00–13:30 · Afternoon",
            "2026-08-05T12:00:00.000-05:00", "2026-08-05T13:30:00.000-05:00", false, pageY));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block_member WHERE block_id = ? AND executable_id = ?",
            Integer.class, blockB, taskX)).isZero();
        assertThat(memberRefreshEvents(taskX)).isEqualTo(2);
    }

    @Test
    @DisplayName("WIG atomicity: a WIG block neither gains members nor donates its lead — reject + Sync Note")
    void wig_blocks_reject_membership_changes() {
        // Given a WIG block (lead measure) and a normal block, both mirrored
        UUID lead = insertExecutable("Lead measure", "LEAD_MEASURE");
        UUID taskX = insertExecutable("Task X", "TASK");
        String pageLead = insertTaskMapping(lead);
        String pageX = insertTaskMapping(taskX);
        UUID wigBlock = insertBlock(NINE, NINE.plusMinutes(60), "PLANNER", "WIG");
        insertMember(wigBlock, lead, 60, 0);
        UUID normalBlock = insertBlock(NINE.plusHours(3), NINE.plusHours(4), "PLANNER", null);
        insertMember(normalBlock, taskX, 60, 0);
        String wigPage = newPageId();
        String normalPage = newPageId();
        insertBlockMapping(wigBlock, wigPage, "stale");
        insertBlockMapping(normalBlock, normalPage, "stale");

        // When the user tries to add X into the WIG block (destination)
        deliver(timeBlockPage(wigPage, "Mié 05 · 09:00–10:00 · WIG",
            "2026-08-05T09:00:00.000-05:00", "2026-08-05T10:00:00.000-05:00", false,
            pageLead, pageX));

        // Then — rejected: membership intact, origin intact, canonical re-assert with a visible note
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block_member WHERE block_id = ?",
            Integer.class, wigBlock)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_time_block WHERE id = ?", String.class, wigBlock))
            .isEqualTo("PLANNER");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'TimeBlockChangedEvent' "
                + "AND payload::text LIKE '%CANONICAL_STATE%' AND payload::text LIKE '%atomic%'",
            Integer.class)).isEqualTo(1);

        // And the lead cannot ride into a normal block either (origin case)
        deliver(timeBlockPage(normalPage, "Mié 05 · 12:00–13:00 · Task X",
            "2026-08-05T12:00:00.000-05:00", "2026-08-05T13:00:00.000-05:00", false,
            pageX, pageLead));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block_member WHERE block_id = ? AND executable_id = ?",
            Integer.class, normalBlock, lead)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block_member WHERE block_id = ?",
            Integer.class, wigBlock)).isEqualTo(1);
    }

    @Test
    @DisplayName("terminal blocks are read-only: an edit rejects and re-asserts with 'frozen history'")
    void terminal_edit_is_rejected() {
        // Given a settled block still mirrored
        UUID taskId = insertExecutable("Done work", "TASK");
        insertTaskMapping(taskId);
        UUID blockId = insertBlock(NINE, NINE.plusMinutes(60), "PLANNER", null,
            "SETTLED", 55, NINE.plusHours(2));
        insertMember(blockId, taskId, 60, 0);
        String pageId = newPageId();
        insertBlockMapping(blockId, pageId, "stale");

        // When the user retimes the settled page
        deliver(timeBlockPage(pageId, "Mié 05 · 15:00–16:00 · Done work",
            "2026-08-05T15:00:00.000-05:00", "2026-08-05T16:00:00.000-05:00", false));

        // Then — frozen: row untouched, re-assert staged with the visible note
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT origin, status, date_start FROM core_time_block WHERE id = ?", blockId);
        assertThat(row.get("status")).isEqualTo("SETTLED");
        assertThat(row.get("origin")).isEqualTo("PLANNER");
        assertThat(instant(row.get("date_start"))).isEqualTo(NINE.toInstant());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE payload::text LIKE '%frozen history%'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("has_more guard: a truncated Tasks relation is never applied — membership survives, canonical re-asserted")
    void has_more_relation_is_not_applied() {
        // Given a two-member block whose page reports a truncated relation
        UUID taskX = insertExecutable("Task X", "TASK");
        UUID taskY = insertExecutable("Task Y", "TASK");
        String pageX = insertTaskMapping(taskX);
        insertTaskMapping(taskY);
        UUID blockId = insertBlock(NINE, NINE.plusMinutes(90), "PLANNER", "Theme");
        insertMember(blockId, taskX, 45, 0);
        insertMember(blockId, taskY, 45, 1);
        String pageId = newPageId();
        insertBlockMapping(blockId, pageId, "stale");

        // When Notion truncates the relation (has_more) down to one visible member
        deliver(timeBlockPage(pageId, "Mié 05 · 09:00–10:30 · Theme",
            "2026-08-05T09:00:00.000-05:00", "2026-08-05T10:30:00.000-05:00", true, pageX));

        // Then — membership untouched, origin untouched, canonical membership re-asserted
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block_member WHERE block_id = ?",
            Integer.class, blockId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_time_block WHERE id = ?", String.class, blockId))
            .isEqualTo("PLANNER");
        assertThat(countEvents("TimeBlockChangedEvent", "SYSTEM")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableUpdatedEvent'",
            Integer.class)).isZero();
    }

    @Test
    @DisplayName("a block created in Notion materializes as USER/PLANNED, relation-only — never creates executables")
    void notion_created_block_is_user_planned_relation_only() {
        // Given a mapped task and an unknown Time Blocks page referencing it
        UUID taskId = insertExecutable("Task X", "TASK");
        String taskPage = insertTaskMapping(taskId);
        int executablesBefore = countExecutables();
        String pageId = newPageId();

        // When
        deliver(timeBlockPage(pageId, "My Notion block",
            "2026-08-05T16:00:00.000-05:00", "2026-08-05T17:00:00.000-05:00", false, taskPage));

        // Then — a USER/PLANNED block with the page title as theme and the task as member
        Map<String, Object> block = jdbcTemplate.queryForMap("""
            SELECT b.id, b.origin, b.status, b.theme
            FROM core_time_block b JOIN sync_mappings m ON m.local_id = b.id
            WHERE m.external_system = 'NOTION' AND m.external_id = ?
            """, pageId);
        assertThat(block.get("origin")).isEqualTo("USER");
        assertThat(block.get("status")).isEqualTo("PLANNED");
        assertThat(block.get("theme")).isEqualTo("My Notion block");
        UUID blockId = (UUID) block.get("id");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block_member WHERE block_id = ? AND executable_id = ?",
            Integer.class, blockId, taskId)).isEqualTo(1);
        // Relation-only: no executable was born from the page
        assertThat(countExecutables()).isEqualTo(executablesBefore);
        assertThat(countEvents("TimeBlockChangedEvent", "NOTION")).isEqualTo(1);

        // Regression core#57: the CREATED change and its immediate canonical re-assertion drain
        // back-to-back BEFORE any WriteCommandResult can close the Apple mapping — the in-flight
        // guard must collapse them into exactly ONE Apple CREATE (no duplicate EKEvent).
        NOTION.stubFor(com.github.tomakehurst.wiremock.client.WireMock.patch(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/v1/pages/.*"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"" + pageId + "\"}")));
        outboxWorker.drainBatch();
        Map<String, Object> command = jdbcTemplate.queryForMap(
            "SELECT count(*) AS commands, max(operation) AS operation "
                + "FROM sync_write_commands WHERE local_id = ?", blockId);
        assertThat(command.get("commands")).isEqualTo(1L);
        assertThat(command.get("operation")).isEqualTo("CREATED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void deliver(String pageJson) {
        consumer.onMessage("""
            {"source_system":"NOTION","message_id":"%s","delivery_channel":"automation",
             "timestamp":"%s",
             "payload":{"source":{"type":"automation","automation_id":"auto-1"},"data":%s}}
            """.formatted(UUID.randomUUID(), EDITED_NOW, pageJson));
    }

    /** A Time Blocks page embedded in an automation delivery (relation ids in page order). */
    private String timeBlockPage(String pageId, String title, String start, String end,
                                 boolean hasMore, String... taskPageIds) {
        StringBuilder relation = new StringBuilder("[");
        for (int index = 0; index < taskPageIds.length; index++) {
            if (index > 0) {
                relation.append(',');
            }
            relation.append("{\"id\":\"").append(taskPageIds[index]).append("\"}");
        }
        relation.append(']');
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Date":{"type":"date","date":{"start":"%s","end":"%s"}},
               "Status":{"type":"select","select":{"name":"Planned"}},
               "Origin":{"type":"select","select":{"name":"Planner"}},
               "Tasks":{"type":"relation","relation":%s,"has_more":%s}}}
            """.formatted(pageId, EDITED_NOW, TB_DB, title, start, end, relation, hasMore);
    }

    private UUID insertExecutable(String name, String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status)
            VALUES (?, ?, ?, ?, 'TODO')
            """, id, DataFixture.SYSTEM_USER_ID, name, type);
        return id;
    }

    private UUID insertBlock(OffsetDateTime start, OffsetDateTime end, String origin,
                             String theme) {
        return insertBlock(start, end, origin, theme, "PLANNED", null, null);
    }

    private UUID insertBlock(OffsetDateTime start, OffsetDateTime end, String origin, String theme,
                             String status, Integer actualMinutes, OffsetDateTime settledAt) {
        UUID id = UUID.randomUUID();
        UUID anchor = jdbcTemplate.queryForObject(
            "SELECT id FROM core_executable LIMIT 1", UUID.class);
        jdbcTemplate.update("""
            INSERT INTO core_time_block
                (id, executable_id, date_start, date_end, status, origin, planned_minutes,
                 theme, actual_duration_minutes, settled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        if (ord == 0) {
            jdbcTemplate.update("UPDATE core_time_block SET executable_id = ? WHERE id = ?",
                executableId, blockId);
        }
    }

    private void insertBlockMapping(UUID blockId, String pageId, String checksum) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum,
                 sync_status, last_synced_at)
            VALUES (?, ?, ?, 'NOTION', ?, ?, 'SYNCED', ?::timestamptz)
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, blockId, pageId, checksum,
            PAST_SYNC);
    }

    private String insertTaskMapping(UUID executableId) {
        String pageId = newPageId();
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum,
                 sync_status, last_synced_at)
            VALUES (?, ?, ?, 'NOTION', ?, 'x', 'SYNCED', ?::timestamptz)
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, executableId, pageId, PAST_SYNC);
        return pageId;
    }

    private static String newPageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int countEvents(String eventType, String sourceSystem) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = ? AND source_system = ?",
            Integer.class, eventType, sourceSystem);
        return count == null ? 0 : count;
    }

    private int memberRefreshEvents(UUID executableId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableUpdatedEvent' "
                + "AND aggregate_id = ? AND source_system = 'SYSTEM'",
            Integer.class, executableId.toString());
        return count == null ? 0 : count;
    }

    private int countExecutables() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable", Integer.class);
        return count == null ? 0 : count;
    }

    private int totalOutboxEvents() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events", Integer.class);
        return count == null ? 0 : count;
    }

    private static Instant instant(Object timestamp) {
        return ((java.sql.Timestamp) timestamp).toInstant();
    }
}
