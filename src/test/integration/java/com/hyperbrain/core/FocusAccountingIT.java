package com.hyperbrain.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hyperbrain.core.application.TimeBlockSettlementService;
import com.hyperbrain.shared.outbox.OutboxWorker;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.SyncEventIngestionService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the ADR-013 focus & progress accounting (#55): real Notion webhook
 * envelopes pushed through the consumer pipeline (dedup → routing → merge → DR chain →
 * persistence + outbox) against a real PostgreSQL. Covers DR-05/DR-06 (single focus, cut,
 * snapshot, re-estimation), DR-07 (materialized progress + imputation) and DR-08 (expiry
 * settlement), including the SQS duplicate-delivery idempotency.
 *
 * <p>Same direct-invocation harness as {@code NotionInboundSyncIT}: cached contexts of other
 * IT classes keep listeners alive on the shared queue and would steal envelopes.
 */
@IntegrationTest
@DisplayName("Focus & progress accounting — DR-05..08 (ADR-013, #55)")
class FocusAccountingIT {

    private static final String TASKS_DB = "1bf8bc9c5d91812b8c97e5e6450858aa";
    private static final String TASKS_DS = "tasksds0000000000000000000000001";

    private static final WireMockServer NOTION = new WireMockServer(
        WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void notionProperties(DynamicPropertyRegistry registry) {
        NOTION.start();
        registry.add("app.sync.notion.enabled", () -> "true");
        registry.add("app.sync.notion.base-url", NOTION::baseUrl);
        registry.add("app.sync.notion.token", () -> "test-token");
        registry.add("app.sync.notion.tasks-data-source-id", () -> TASKS_DS);
        registry.add("app.sync.notion.min-request-interval-ms", () -> "0");
        registry.add("app.sync.notion.backoff-base-ms", () -> "10");
        registry.add("app.sync.notion.max-attempts", () -> "2");
    }

    @AfterAll
    static void stopServer() {
        NOTION.stop();
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SyncEventIngestionService ingestionService;
    @Autowired private NotionEnvelopeNormalizer normalizer;
    @Autowired private TimeBlockSettlementService settlementService;
    @Autowired private OutboxWorker outboxWorker;

    private SqsConsumer consumer;

    @BeforeEach
    void cleanState() throws Exception {
        consumer = new SqsConsumer(objectMapper, ingestionService, normalizer);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        NOTION.resetAll();
    }

    @Test
    @DisplayName("DR-05: activating a task auto-opens its ACTIVE/FOCUS block")
    void activation_opens_focus_block() {
        String pageA = newPageId();
        activate(pageA, "Deep work", 3.5, "2026-07-08T10:00:00.000Z");

        UUID taskA = localId(pageA);
        Map<String, Object> block = jdbcTemplate.queryForMap(
            "SELECT status, origin, date_end FROM core_time_block WHERE executable_id = ?", taskA);
        assertThat(block.get("status")).isEqualTo("ACTIVE");
        assertThat(block.get("origin")).isEqualTo("FOCUS");
        assertThat(block.get("date_end")).isNull();
    }

    @Test
    @DisplayName("DR-05 + DR-06: activating B cuts A — settled block, frozen snapshot, preserved effort, mirrored events")
    void focus_switch_cuts_previous_focus() {
        String pageA = newPageId();
        String pageB = newPageId();
        activate(pageA, "Deep work", 3.5, "2026-07-08T10:00:00.000Z");
        UUID taskA = localId(pageA);
        // A carries a full execution profile — the cut must preserve every effort label
        seedProfile(taskA, 4, 3, 5);

        // Create B first, then clear the outbox so only the focus-switch's own SYSTEM events remain
        // to be asserted — the setup activations' #66a score reflections (a separate, verified concern)
        // are not part of what this DR-05/DR-06 test pins down.
        deliverAutomation(pageB, taskPage(pageB, "Urgent fix", "Not started", 1.0, null,
            "2026-07-08T09:00:00.000Z"));
        UUID taskB = localId(pageB);
        jdbcTemplate.update("DELETE FROM outbox_events");

        // Activating B cuts A (the focus switch)
        deliverAutomation(pageB, taskPage(pageB, "Urgent fix", "In progress", 1.0, null,
            "2026-07-08T11:00:00.000Z"));

        // A's block settled by the cut
        Map<String, Object> blockA = jdbcTemplate.queryForMap(
            "SELECT status, settled_at, actual_duration_minutes FROM core_time_block WHERE executable_id = ?",
            taskA);
        assertThat(blockA.get("status")).isEqualTo("SETTLED");
        assertThat(blockA.get("settled_at")).isNotNull();
        assertThat(blockA.get("actual_duration_minutes")).isNotNull();

        // Snapshot subtask frozen under A with the original labels
        Map<String, Object> snapshot = jdbcTemplate.queryForMap("""
            SELECT id, parent_id, type, status, effort_score, description, last_completed_at
            FROM core_executable WHERE system_generated = true
            """);
        assertThat(snapshot.get("parent_id")).isEqualTo(taskA);
        assertThat(snapshot.get("type")).isEqualTo("TASK");
        assertThat(snapshot.get("status")).isEqualTo("DONE");
        assertThat(snapshot.get("effort_score")).isEqualTo(3.5);
        assertThat((String) snapshot.get("description")).startsWith("[focus] ");
        assertThat(snapshot.get("last_completed_at")).isNotNull();

        // A stays IN_PROGRESS with its effort preserved as last known value, flagged for re-estimation
        Map<String, Object> cutA = jdbcTemplate.queryForMap(
            "SELECT status, effort_score, pending_reestimation FROM core_executable WHERE id = ?", taskA);
        assertThat(cutA.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(cutA.get("effort_score")).isEqualTo(3.5);
        assertThat(cutA.get("pending_reestimation")).isEqualTo(true);

        // And the execution profile of the cut task is untouched (no data loss on the labels)
        Map<String, Object> profileA = jdbcTemplate.queryForMap(
            "SELECT energy_drain, mental_load, impact FROM core_execution_profile WHERE executable_id = ?",
            taskA);
        assertThat(profileA.get("energy_drain")).isEqualTo(4);
        assertThat(profileA.get("mental_load")).isEqualTo(3);
        assertThat(profileA.get("impact")).isEqualTo(5);

        // B took the focus with its own ACTIVE/FOCUS block
        String blockBStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM core_time_block WHERE executable_id = ?", String.class, taskB);
        assertThat(blockBStatus).isEqualTo("ACTIVE");

        // Outbox: SYSTEM-originated snapshot mirror, preserved-state mirror, focus event, settlement,
        // and the canonical write-back reflection for B's ingestion (always staged for NOTION origin
        // so the Core state reaches Notion past RF-17 loop protection).
        List<Map<String, Object>> systemEvents = jdbcTemplate.queryForList(
            "SELECT event_type, aggregate_id FROM outbox_events WHERE source_system = 'SYSTEM'");
        assertThat(systemEvents).extracting(row -> row.get("event_type"))
            .containsExactlyInAnyOrder("ExecutableCreatedEvent", "ExecutableUpdatedEvent",
                "ExecutableUpdatedEvent", "FocusSwitchedEvent", "TimeBlockSettledEvent");
        assertThat(systemEvents)
            .filteredOn(row -> row.get("event_type").equals("ExecutableCreatedEvent"))
            .extracting(row -> row.get("aggregate_id"))
            .containsExactly(snapshot.get("id").toString());
    }

    @Test
    @DisplayName("DR-06 no-regression: the cut mirror carries the preserved effort to Notion, not a clear")
    void cut_mirror_preserves_effort_towards_notion() throws Exception {
        String pageA = newPageId();
        String pageB = newPageId();
        activate(pageA, "Deep work", 3.5, "2026-07-08T10:00:00.000Z");
        UUID taskA = localId(pageA);
        seedProfile(taskA, 4, 3, 5);
        // Drain the activation events so A is already mapped in Notion before the cut
        stubCreatePage();
        outboxWorker.drainBatch();
        NOTION.resetAll();
        stubPatchAnyPage();

        // Activating B cuts A, which appends the preserved-state mirror event for A
        activate(pageB, "Urgent fix", 1.0, "2026-07-08T11:00:00.000Z");
        stubCreatePage();
        outboxWorker.drainBatch();

        // The Notion write-back for A must carry the preserved labels, never a clear
        LoggedRequest patchA = singlePatchFor(taskA);
        JsonNode props = objectMapper.readTree(patchA.getBodyAsString()).path("properties");
        assertThat(props.path("Effort").path("number").asDouble()).isEqualTo(3.5);
        assertThat(props.path("Effort").path("number").isNull()).isFalse();
        assertThat(props.path("Energy").path("select").path("name").asText()).isEqualTo("Demanding");
        assertThat(props.path("Mental Load").path("select").path("name").asText()).isEqualTo("Analysis");
        assertThat(props.path("Impact").path("select").path("name").asText()).isEqualTo("Critical");
    }

    @Test
    @DisplayName("DR-05 idempotency: a duplicate SQS delivery does not cut twice")
    void duplicate_delivery_is_idempotent() {
        String pageA = newPageId();
        String pageB = newPageId();
        activate(pageA, "Deep work", 3.5, "2026-07-08T10:00:00.000Z");
        deliverAutomation(pageB, taskPage(pageB, "Urgent fix", "Not started", 1.0, null,
            "2026-07-08T10:59:00.000Z"));
        String envelope = automationEnvelope(UUID.randomUUID().toString(), pageB,
            taskPage(pageB, "Urgent fix", "In progress", 1.0, null, "2026-07-08T11:00:00.000Z"));

        consumer.onMessage(envelope);
        consumer.onMessage(envelope);

        Integer snapshots = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE system_generated = true", Integer.class);
        assertThat(snapshots).isEqualTo(1);
    }

    @Test
    @DisplayName("DR-06 confirmation: fresh effort from Notion clears pending_reestimation")
    void human_effort_confirms_reestimation() {
        String pageA = newPageId();
        String pageB = newPageId();
        activate(pageA, "Deep work", 3.5, "2026-07-08T10:00:00.000Z");
        activate(pageB, "Urgent fix", 1.0, "2026-07-08T11:00:00.000Z");
        UUID taskA = localId(pageA);
        assertThat(pendingReestimation(taskA)).isTrue();

        deliverAutomation(pageA, taskPage(pageA, "Deep work", "In progress", 2.0, null,
            "2026-07-08T12:00:00.000Z"));

        assertThat(pendingReestimation(taskA)).isFalse();
        Double effort = jdbcTemplate.queryForObject(
            "SELECT effort_score FROM core_executable WHERE id = ?", Double.class, taskA);
        assertThat(effort).isEqualTo(2.0);
    }

    @Test
    @DisplayName("DR-07: completing a user subtask materializes the parent progress and emits SubtaskCompletedEvent")
    void subtask_completion_updates_progress() {
        String parentPage = newPageId();
        String subtaskPage = newPageId();
        deliverAutomation(parentPage, taskPage(parentPage, "Project-ish", "Not started", null, null,
            "2026-07-08T10:00:00.000Z"));
        UUID parent = localId(parentPage);
        deliverAutomation(subtaskPage, taskPage(subtaskPage, "Step 1", "Not started", null, parentPage,
            "2026-07-08T10:01:00.000Z"));
        UUID subtask = localId(subtaskPage);

        Double before = jdbcTemplate.queryForObject(
            "SELECT progress FROM core_executable WHERE id = ?", Double.class, parent);
        assertThat(before).isEqualTo(0.0);

        deliverAutomation(subtaskPage, completedTaskPage(subtaskPage, "Step 1", parentPage,
            "2026-07-08T10:30:00.000Z"));

        Map<String, Object> parentRow = jdbcTemplate.queryForMap(
            "SELECT progress FROM core_executable WHERE id = ?", parent);
        assertThat(parentRow.get("progress")).isEqualTo(1.0);
        Map<String, Object> subtaskRow = jdbcTemplate.queryForMap(
            "SELECT last_completed_at, imputed_time_block_id FROM core_executable WHERE id = ?", subtask);
        assertThat(subtaskRow.get("last_completed_at")).isNotNull();
        // The parent was never the focus: unplanned work, no imputation
        assertThat(subtaskRow.get("imputed_time_block_id")).isNull();
        Integer events = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'SubtaskCompletedEvent'",
            Integer.class);
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("DR-08: the expiry sweep settles due blocks as EXPIRED and imputes the window")
    void expiry_settles_due_blocks_and_imputes() {
        String parentPage = newPageId();
        String subtaskPage = newPageId();
        deliverAutomation(parentPage, taskPage(parentPage, "Planned work", "Not started", null, null,
            "2026-07-08T10:00:00.000Z"));
        UUID parent = localId(parentPage);
        deliverAutomation(subtaskPage, taskPage(subtaskPage, "Step 1", "Not started", null, parentPage,
            "2026-07-08T10:01:00.000Z"));
        UUID subtask = localId(subtaskPage);

        // A planned block whose window already passed, covering the subtask completion
        UUID blockId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().minusHours(2);
        OffsetDateTime end = OffsetDateTime.now().minusHours(1);
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin, planned_minutes)
            VALUES (?, ?, ?, ?, 'ACTIVE', 'PLANNER', 60)
            """, blockId, parent, start, end);
        deliverAutomation(subtaskPage, completedTaskPage(subtaskPage, "Step 1", parentPage,
            "2026-07-08T10:30:00.000Z"));
        // Force the completion clock inside the block window (the rule stamped "now")
        jdbcTemplate.update(
            "UPDATE core_executable SET last_completed_at = ?, imputed_time_block_id = NULL WHERE id = ?",
            start.plusMinutes(30), subtask);

        int settled = settlementService.expireDueBlocks(OffsetDateTime.now());

        assertThat(settled).isEqualTo(1);
        Map<String, Object> block = jdbcTemplate.queryForMap(
            "SELECT status, actual_duration_minutes, settled_at FROM core_time_block WHERE id = ?",
            blockId);
        assertThat(block.get("status")).isEqualTo("EXPIRED");
        assertThat(block.get("actual_duration_minutes")).isEqualTo(60);
        assertThat(block.get("settled_at")).isNotNull();
        UUID imputed = jdbcTemplate.queryForObject(
            "SELECT imputed_time_block_id FROM core_executable WHERE id = ?", UUID.class, subtask);
        assertThat(imputed).isEqualTo(blockId);
        Integer events = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'TimeBlockSettledEvent'",
            Integer.class);
        assertThat(events).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void deliverAutomation(String pageId, String pageJson) {
        consumer.onMessage(automationEnvelope(UUID.randomUUID().toString(), pageId, pageJson));
    }

    /**
     * Creates the page as "Not started" and then flips it to "In progress": the realistic
     * activation flow (DR-05 opens the FOCUS block on the UPDATE, once the row exists).
     */
    private void activate(String pageId, String name, Double effort, String activatedAt) {
        deliverAutomation(pageId, taskPage(pageId, name, "Not started", effort, null,
            "2026-07-08T09:00:00.000Z"));
        deliverAutomation(pageId, taskPage(pageId, name, "In progress", effort, null, activatedAt));
    }

    private static String automationEnvelope(String messageId, String pageId, String pageJson) {
        return """
            {"source_system":"NOTION","message_id":"%s","delivery_channel":"automation",
             "timestamp":"2026-07-08T10:00:00Z",
             "payload":{"source":{"type":"automation","automation_id":"auto-1"},"data":%s}}
            """.formatted(messageId, pageJson);
    }

    private String taskPage(String pageId, String name, String status, Double effort,
                            String parentPageId, String lastEditedTime) {
        String effortProp = effort != null
            ? ",\"Effort\":{\"type\":\"number\",\"number\":" + effort + "}"
            : "";
        String parentProp = parentPageId != null
            ? ",\"Parent Task\":{\"type\":\"relation\",\"relation\":[{\"id\":\"" + parentPageId + "\"}]}"
            : "";
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"%s"}},
               "Complete":{"type":"checkbox","checkbox":false},
               "Type":{"type":"select","select":{"name":"Task"}}%s%s}}
            """.formatted(pageId, lastEditedTime, TASKS_DB, name, status, effortProp, parentProp);
    }

    private String completedTaskPage(String pageId, String name, String parentPageId,
                                     String lastEditedTime) {
        String parentProp = parentPageId != null
            ? ",\"Parent Task\":{\"type\":\"relation\",\"relation\":[{\"id\":\"" + parentPageId + "\"}]}"
            : "";
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"Done"}},
               "Complete":{"type":"checkbox","checkbox":true},
               "Type":{"type":"select","select":{"name":"Task"}}%s}}
            """.formatted(pageId, lastEditedTime, TASKS_DB, name, parentProp);
    }

    private UUID localId(String pageId) {
        return jdbcTemplate.queryForObject(
            "SELECT local_id FROM sync_mappings WHERE external_system = 'NOTION' AND external_id = ?",
            UUID.class, pageId);
    }

    private void seedProfile(UUID executableId, int energyDrain, int mentalLoad, int impact) {
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, energy_drain, mental_load, impact)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (executable_id) DO UPDATE
              SET energy_drain = EXCLUDED.energy_drain,
                  mental_load = EXCLUDED.mental_load,
                  impact = EXCLUDED.impact
            """, executableId, energyDrain, mentalLoad, impact);
    }

    /** Stubs the Notion page creation with a deterministic page id derived from the request. */
    private void stubCreatePage() {
        NOTION.stubFor(post(urlPathEqualTo("/v1/pages"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"" + newPageId() + "\"}")));
    }

    private void stubPatchAnyPage() {
        NOTION.stubFor(WireMock.patch(WireMock.urlPathMatching("/v1/pages/.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\"}")));
    }

    /** Returns the single PATCH request Notion received for the given local entity's mapped page. */
    private LoggedRequest singlePatchFor(UUID localId) {
        String externalId = jdbcTemplate.queryForObject(
            "SELECT external_id FROM sync_mappings WHERE external_system = 'NOTION' AND local_id = ?",
            String.class, localId);
        List<LoggedRequest> requests = NOTION.findAll(
            WireMock.patchRequestedFor(urlEqualTo("/v1/pages/" + externalId)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private boolean pendingReestimation(UUID executableId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT pending_reestimation FROM core_executable WHERE id = ?",
            Boolean.class, executableId));
    }

    private static String newPageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
