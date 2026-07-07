package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the Notion inbound sync (HU-14): real {@code NotionWebhookEnvelope}
 * bodies pushed through the consumer pipeline (normalization → dedup → routing → handlers)
 * against a real PostgreSQL and a stubbed Notion API (WireMock). Covers the issue scenarios
 * 1-6 plus the burst/out-of-order convergence of CA-31 and the echo discard of CA-20.
 *
 * <p>The consumer entry point is invoked directly instead of through the shared LocalStack
 * queue: cached contexts of other IT classes keep listeners alive on {@code sync-events.fifo}
 * and would steal (and mis-acknowledge) Notion envelopes, since those contexts run without
 * the Notion handlers. Direct invocation keeps the burst scenario deterministic while
 * preserving the exact FIFO semantics (in-order per {@code MessageGroupId} = page id, with
 * duplicates and cross-group reordering simulated explicitly).
 */
@IntegrationTest
@DisplayName("Notion inbound sync — webhook envelopes → PostgreSQL (HU-14)")
class NotionInboundSyncIT {

    private static final String TASKS_DB = "1bf8bc9c5d91812b8c97e5e6450858aa";
    private static final String CYCLES_DB = "1bf8bc9c5d9181d882cfe1f4aa38f295";
    private static final String TASKS_DS = "1bf8bc9c5d918171b7ea000b7e326082";
    private static final String CYCLES_DS = "1bf8bc9c5d9181e78737000b45812f45";

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
        registry.add("app.sync.notion.tasks-database-id", () -> TASKS_DB);
        registry.add("app.sync.notion.cycles-database-id", () -> CYCLES_DB);
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

    private SqsConsumer consumer;

    @BeforeEach
    void cleanState() throws Exception {
        consumer = new SqsConsumer(objectMapper, ingestionService, normalizer);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        NOTION.resetAll();
    }

    @Test
    @DisplayName("scenario 1 — CREATE: an automation delivery creates core_executable + sync_mapping and stages the Apple write-back")
    void automation_create_persists_executable() {
        String pageId = newPageId();

        deliverAutomation(pageId, taskPage(pageId, "Plan sprint", "Not started", false, "Task",
            "2026-07-07T15:00:00.000Z", null));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT e.name, e.type, e.status, m.external_id, m.last_known_checksum, m.last_synced_at
            FROM core_executable e JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_system = 'NOTION' AND m.external_id = ?
            """, pageId);
        assertThat(row.get("name")).isEqualTo("Plan sprint");
        assertThat(row.get("type")).isEqualTo("TASK");
        assertThat(row.get("status")).isEqualTo("TODO");
        assertThat((String) row.get("last_known_checksum")).hasSize(64);

        // The outbox carries the CREATED event with source NOTION (Apple picks it up, HU-09c)
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
            "SELECT event_type, source_system FROM outbox_events");
        assertThat(outbox.get("event_type")).isEqualTo("ExecutableCreatedEvent");
        assertThat(outbox.get("source_system")).isEqualTo("NOTION");
    }

    @Test
    @DisplayName("scenario 2 — UPDATE via subscription (thin delivery): the page is fetched from the API and updated")
    void subscription_update_fetches_page() {
        // Given an already-synced task
        String pageId = newPageId();
        deliverAutomation(pageId, taskPage(pageId, "Original", "Not started", false, "Task",
            "2026-07-07T15:00:00.000Z", null));

        // And the API returns a newer state for the thin delivery
        NOTION.stubFor(get(urlEqualTo("/v1/pages/" + pageId))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(taskPage(pageId, "Renamed in Notion", "In progress", false, "Task",
                    "2026-07-07T15:05:00.000Z", null))));

        // When
        deliverSubscription(pageId, TASKS_DS);

        // Then
        assertThat(mappedName(pageId)).isEqualTo("Renamed in Notion");
        assertThat(countExecutables()).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 3 — completing in Notion (Complete checkbox) marks the executable DONE")
    void completion_marks_done() {
        String pageId = newPageId();
        deliverAutomation(pageId, taskPage(pageId, "Task", "In progress", false, "Task",
            "2026-07-07T15:00:00.000Z", null));

        // Complete wins over a stale Status (CA-5)
        deliverAutomation(pageId, taskPage(pageId, "Task", "In progress", true, "Task",
            "2026-07-07T15:01:00.000Z", null));

        String status = jdbcTemplate.queryForObject("""
            SELECT e.status FROM core_executable e JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_id = ?
            """, String.class, pageId);
        assertThat(status).isEqualTo("DONE");
    }

    @Test
    @DisplayName("scenario 4 — DELETE: a trashed page removes the entity and its mapping (CA-7)")
    void trashed_page_deletes_entity() {
        String pageId = newPageId();
        deliverAutomation(pageId, taskPage(pageId, "Doomed", "Not started", false, "Task",
            "2026-07-07T15:00:00.000Z", null));
        assertThat(countMapped(pageId)).isEqualTo(1);

        deliverAutomation(pageId, trashedPage(pageId, "2026-07-07T15:02:00.000Z"));

        assertThat(countMapped(pageId)).isZero();
        assertThat(countExecutables()).isZero();
        Integer deletions = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableDeletedEvent'",
            Integer.class);
        assertThat(deletions).isEqualTo(1);
    }

    @Test
    @DisplayName("subscription DELETE: a 404 on fetch is processed as DELETED")
    void vanished_page_on_fetch_deletes_entity() {
        String pageId = newPageId();
        deliverAutomation(pageId, taskPage(pageId, "Ghost", "Not started", false, "Task",
            "2026-07-07T15:00:00.000Z", null));
        NOTION.stubFor(get(urlEqualTo("/v1/pages/" + pageId))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\"}")));

        deliverSubscription(pageId, TASKS_DS);

        assertThat(countMapped(pageId)).isZero();
        assertThat(countExecutables()).isZero();
    }

    @Test
    @DisplayName("scenario 5 — Cycles: a task referencing an unmapped cycle imports the cycle first (CA-5)")
    void unmapped_cycle_is_imported_before_the_task() {
        String cyclePageId = newPageId();
        String taskPageId = newPageId();
        NOTION.stubFor(get(urlEqualTo("/v1/pages/" + cyclePageId))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(cyclePage(cyclePageId, "Sprint 2", "MCI", false,
                    "2026-07-07T14:00:00.000Z"))));

        deliverAutomation(taskPageId, taskPage(taskPageId, "Task in cycle", "Not started", false,
            "Task", "2026-07-07T15:00:00.000Z", cyclePageId));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT c.name AS cycle_name, c.type AS cycle_type
            FROM core_executable e
            JOIN core_cycle c ON c.id = e.cycle_id
            JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_id = ?
            """, taskPageId);
        assertThat(row.get("cycle_name")).isEqualTo("Sprint 2");
        assertThat(row.get("cycle_type")).isEqualTo("MCI");
        assertThat(countMapped(cyclePageId)).isEqualTo(1);
        assertThat(countMapped(taskPageId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Cycles direct: an automation delivery of the Cycles DB upserts core_cycle (ADR-011)")
    void cycle_delivery_upserts_cycle() {
        String pageId = newPageId();

        deliverAutomation(pageId, cyclePage(pageId, "Q3 routine", "Routine", true,
            "2026-07-07T15:00:00.000Z"));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT c.name, c.type, c.status FROM core_cycle c
            JOIN sync_mappings m ON m.local_id = c.id
            WHERE m.external_id = ?
            """, pageId);
        assertThat(row.get("name")).isEqualTo("Q3 routine");
        assertThat(row.get("type")).isEqualTo("ROUTINE");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("scenario 6 / CA-31 — burst with a duplicate and an out-of-order delivery converges to the latest state")
    void burst_converges_to_latest_state() {
        String pageId = newPageId();

        // Five rapid edits, one webhook each, delivered in FIFO order (MessageGroupId = page id).
        // Notion truncates last_edited_time to the minute, so all five share one timestamp —
        // convergence relies on the checksum discard, never on sub-minute ordering (CA-29).
        for (int edit = 1; edit <= 5; edit++) {
            deliver(automationEnvelope("burst-" + edit, pageId,
                taskPage(pageId, "Edit " + edit, "In progress", false, "Task",
                    "2026-07-07T15:00:00.000Z", null)));
        }
        // A redelivery of the third webhook (same message_id) — consumer dedup must absorb it
        deliver(automationEnvelope("burst-3", pageId,
            taskPage(pageId, "Edit 3", "In progress", false, "Task",
                "2026-07-07T15:00:00.000Z", null)));
        // An out-of-order delivery from an earlier minute (new message_id, strictly older
        // last_edited_time) — the only case the monotonicity guard can and must discard
        deliver(automationEnvelope("burst-late", pageId,
            taskPage(pageId, "Edit 2", "In progress", false, "Task",
                "2026-07-07T14:59:00.000Z", null)));

        // Then: one executable, final state = the most recent edit, no duplicates, no regression
        assertThat(countExecutables()).isEqualTo(1);
        assertThat(countMapped(pageId)).isEqualTo(1);
        assertThat(mappedName(pageId)).isEqualTo("Edit 5");
        // Dedup recorded the 5 edits + the stale delivery; the duplicate never re-processed
        Integer processed = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message", Integer.class);
        assertThat(processed).isEqualTo(6);
        // Outbox carries only the net changes: 1 CREATE + 4 UPDATEs
        Integer outbox = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outbox).isEqualTo(5);
    }

    @Test
    @DisplayName("ADR-012 D1 — renaming in Notion never regresses a richer domain status (loss-aware merge)")
    void rename_does_not_regress_domain_status() {
        // Given a synced task whose domain state is richer than what Notion can express:
        // PLANNED projects to "Not started", priority was computed domain-side
        String pageId = newPageId();
        deliverAutomation(pageId, taskPage(pageId, "Original", "Not started", false, "Task",
            "2026-07-07T15:00:00.000Z", null));
        jdbcTemplate.update("""
            UPDATE core_executable SET status = 'PLANNED', priority_score = 0.9
            WHERE id = (SELECT local_id FROM sync_mappings WHERE external_id = ?)
            """, pageId);

        // When the user only renames the page (Status still projects as "Not started")
        deliverAutomation(pageId, taskPage(pageId, "Renamed", "Not started", false, "Task",
            "2026-07-07T15:01:00.000Z", null));

        // Then the rename applies and the domain-owned state survives
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT e.name, e.status, e.priority_score
            FROM core_executable e JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_id = ?
            """, pageId);
        assertThat(row.get("name")).isEqualTo("Renamed");
        assertThat(row.get("status")).isEqualTo("PLANNED");
        assertThat(row.get("priority_score")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("CA-20 — a webhook with state identical to the last synced one is discarded by checksum")
    void identical_state_is_discarded() {
        String pageId = newPageId();
        deliverAutomation(pageId, taskPage(pageId, "Stable", "Not started", false, "Task",
            "2026-07-07T15:00:00.000Z", null));

        // Same state, newer last_edited_time (so the monotonicity guard does not kick in first)
        deliverAutomation(pageId, taskPage(pageId, "Stable", "Not started", false, "Task",
            "2026-07-07T15:05:00.000Z", null));

        // No second outbox event and no double effect
        Integer outbox = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outbox).isEqualTo(1);
        assertThat(countExecutables()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA-19 — a redelivered envelope (same message_id) is idempotent via processed_message")
    void duplicate_envelope_is_deduplicated() {
        String pageId = newPageId();
        String envelope = automationEnvelope("dup-1", pageId,
            taskPage(pageId, "Once", "Not started", false, "Task",
                "2026-07-07T15:00:00.000Z", null));

        deliver(envelope);
        deliver(envelope);

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message WHERE message_id = 'dup-1'", Integer.class);
        assertThat(processed).isEqualTo(1);
        assertThat(countExecutables()).isEqualTo(1);
        Integer outbox = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outbox).isEqualTo(1);
    }

    @Test
    @DisplayName("CA-1 — deliveries of an unmapped database are discarded with a log and never routed")
    void unmapped_database_is_discarded() {
        String pageId = newPageId();
        deliver("""
            {"source_system":"NOTION","message_id":"foreign-1","delivery_channel":"automation",
             "timestamp":"2026-07-07T15:00:00Z",
             "payload":{"source":{"type":"automation"},"data":{"object":"page","id":"%s",
               "parent":{"type":"database_id","database_id":"ffffffffffffffffffffffffffffffff"},
               "properties":{}}}}
            """.formatted(pageId));

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message", Integer.class);
        assertThat(processed).isZero();
        assertThat(countExecutables()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void deliver(String envelopeBody) {
        consumer.onMessage(envelopeBody);
    }

    private void deliverAutomation(String pageId, String pageJson) {
        deliver(automationEnvelope(UUID.randomUUID().toString(), pageId, pageJson));
    }

    private void deliverSubscription(String pageId, String dataSourceId) {
        deliver("""
            {"source_system":"NOTION","message_id":"%s","delivery_channel":"subscription",
             "timestamp":"2026-07-07T15:00:00Z",
             "payload":{"id":"%s","type":"page.properties_updated",
               "entity":{"id":"%s","type":"page"},
               "data":{"parent":{"id":"%s","type":"data_source"}}}}
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), pageId, dataSourceId));
    }

    private static String automationEnvelope(String messageId, String pageId, String pageJson) {
        return """
            {"source_system":"NOTION","message_id":"%s","delivery_channel":"automation",
             "timestamp":"2026-07-07T15:00:00Z",
             "payload":{"source":{"type":"automation","automation_id":"auto-1"},"data":%s}}
            """.formatted(messageId, pageJson);
    }

    private String taskPage(String pageId, String name, String status, boolean complete,
                            String type, String lastEditedTime, String cyclePageId) {
        String cycleRelation = cyclePageId != null
            ? ",\"Cycle\":{\"type\":\"relation\",\"relation\":[{\"id\":\"" + cyclePageId + "\"}]}"
            : "";
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"%s"}},
               "Complete":{"type":"checkbox","checkbox":%s},
               "Type":{"type":"select","select":{"name":"%s"}}%s}}
            """.formatted(pageId, lastEditedTime, TASKS_DB, name, status, complete, type, cycleRelation);
    }

    private String cyclePage(String pageId, String name, String type, boolean inactive,
                             String lastEditedTime) {
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Type":{"type":"select","select":{"name":"%s"}},
               "Date":{"type":"date","date":{"start":"2026-07-01","end":"2026-07-14"}},
               "Inactive":{"type":"checkbox","checkbox":%s}}}
            """.formatted(pageId, lastEditedTime, CYCLES_DB, name, type, inactive);
    }

    private String trashedPage(String pageId, String lastEditedTime) {
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":true,
             "parent":{"type":"database_id","database_id":"%s"},"properties":{}}
            """.formatted(pageId, lastEditedTime, TASKS_DB);
    }

    private String mappedName(String pageId) {
        return jdbcTemplate.queryForObject("""
            SELECT e.name FROM core_executable e JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_id = ?
            """, String.class, pageId);
    }

    private int countMapped(String pageId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system = 'NOTION' AND external_id = ?",
            Integer.class, pageId);
        return count == null ? 0 : count;
    }

    private int countExecutables() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable", Integer.class);
        return count == null ? 0 : count;
    }

    private static String newPageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
