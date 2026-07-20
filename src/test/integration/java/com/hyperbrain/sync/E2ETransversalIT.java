package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hyperbrain.shared.outbox.OutboxWorker;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.SyncEventIngestionService;
import com.hyperbrain.sync.infrastructure.NotionEnvelopeNormalizer;
import com.hyperbrain.sync.infrastructure.SqsConsumer;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end transversal pipeline tests (HU-15 CA-1): the full iOS ↔ PG ↔ Notion channel
 * exercised against Testcontainers (PostgreSQL), LocalStack (SQS) and a WireMock Notion stub.
 *
 * <p>Direction A (Apple → PG → Notion): an Apple SyncEvent is injected directly into the
 * consumer, the outbox is drained, and the correct Notion REST call is asserted via WireMock.
 * The drain must not emit any WriteCommand on {@code apple-commands.fifo} (RF-17 loop protection).
 *
 * <p>Direction B (Notion → PG → Apple): a Notion automation envelope is injected, the outbox
 * is drained, and the correct WriteCommand is asserted on {@code apple-commands.fifo}. The
 * WireMock Notion stub must receive no calls (RF-17 loop protection).
 *
 * <p>The consumer is invoked directly (not via SQS) to avoid competing-listener interference
 * with other IT classes that enable {@code app.sync.consumer.enabled}. The outbox worker runs
 * synchronously via {@link OutboxWorker#drainBatch()}, keeping assertions deterministic.
 */
@IntegrationTest
@DisplayName("E2E transversal — iOS ↔ PG ↔ Notion (HU-15 CA-1)")
class E2ETransversalIT {

    private static final String TASKS_DB = "1bf8bc9c5d91812b8c97e5e6450858aa";
    private static final String CYCLES_DB = "1bf8bc9c5d9181d882cfe1f4aa38f295";
    private static final String TASKS_DS = "1bf8bc9c5d918171b7ea000b7e326082";
    private static final String CYCLES_DS = "1bf8bc9c5d9181e78737000b45812f45";
    private static final String COMMANDS_QUEUE = "apple-commands.fifo";

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
        registry.add("app.sync.notion.max-attempts", () -> "3");
    }

    @AfterAll
    static void stopNotion() {
        NOTION.stop();
    }

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SyncEventIngestionService ingestionService;
    @Autowired private NotionEnvelopeNormalizer normalizer;

    private SqsConsumer consumer;

    @BeforeEach
    void cleanState() throws Exception {
        consumer = new SqsConsumer(objectMapper, ingestionService, normalizer);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        NOTION.resetAll();
        drainQueue(COMMANDS_QUEUE);
    }

    // ── Direction A: Apple → PG → Notion ──────────────────────────────────────

    @Test
    @DisplayName("A1 — REMINDER created in Apple appears in Notion with correct fields (E2E-A1)")
    void reminder_created_in_apple_appears_in_notion() throws Exception {
        String entityId = "EKReminder-" + UUID.randomUUID();
        String pageId = newPageId();
        stubCreateTaskPage(pageId);

        consumer.onMessage(reminder("CREATED", entityId, "E2E task A1", false));
        outboxWorker.drainBatch();

        LoggedRequest req = singlePost("/v1/pages");
        JsonNode props = objectMapper.readTree(req.getBodyAsString()).path("properties");
        assertThat(props.path("Name").path("title").get(0).path("text").path("content").asText())
            .isEqualTo("E2E task A1");
        assertThat(props.path("Type").path("select").path("name").asText()).isEqualTo("Task");
        assertThat(props.path("Complete").path("checkbox").asBoolean()).isFalse();
        // Both APPLE and NOTION sync_mappings must now be in place
        assertThat(countMappingsBySystem(entityId, "APPLE")).isEqualTo(1);
        assertThat(countNotionMappings()).isEqualTo(1);
        // Loop protection: no WriteCommand sent back to Apple (RF-17)
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
    }

    @Test
    @DisplayName("A2 — REMINDER updated in Apple patches the Notion page (E2E-A2)")
    void reminder_updated_in_apple_patches_notion() throws Exception {
        String entityId = "EKReminder-" + UUID.randomUUID();
        String pageId = newPageId();
        // Establish state: create first so a NOTION mapping exists
        stubCreateTaskPage(pageId);
        consumer.onMessage(reminder("CREATED", entityId, "Original A2", false));
        outboxWorker.drainBatch();
        NOTION.resetAll();
        stubPatchPage(pageId);

        consumer.onMessage(reminder("UPDATED", entityId, "Updated A2", false));
        outboxWorker.drainBatch();

        singlePatch("/v1/pages/" + pageId);
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
    }

    @Test
    @DisplayName("A3 — REMINDER completed in Apple marks Notion Complete=true and Status=Done (E2E-A3)")
    void reminder_completed_in_apple_marks_notion_done() throws Exception {
        String entityId = "EKReminder-" + UUID.randomUUID();
        String pageId = newPageId();
        stubCreateTaskPage(pageId);
        consumer.onMessage(reminder("CREATED", entityId, "Task A3", false));
        outboxWorker.drainBatch();
        NOTION.resetAll();
        stubPatchPage(pageId);

        consumer.onMessage(reminder("UPDATED", entityId, "Task A3", true));
        outboxWorker.drainBatch();

        LoggedRequest req = singlePatch("/v1/pages/" + pageId);
        JsonNode props = objectMapper.readTree(req.getBodyAsString()).path("properties");
        assertThat(props.path("Complete").path("checkbox").asBoolean()).isTrue();
        assertThat(props.path("Status").path("status").path("name").asText()).isEqualTo("Done");
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
    }

    @Test
    @DisplayName("A4 — REMINDER deleted in Apple archives the Notion page and removes mappings (E2E-A4)")
    void reminder_deleted_in_apple_archives_notion_page() throws Exception {
        String entityId = "EKReminder-" + UUID.randomUUID();
        String pageId = newPageId();
        stubCreateTaskPage(pageId);
        consumer.onMessage(reminder("CREATED", entityId, "Task A4", false));
        outboxWorker.drainBatch();
        NOTION.resetAll();
        stubPatchPage(pageId);

        consumer.onMessage(reminderDeleted(entityId));
        outboxWorker.drainBatch();

        LoggedRequest req = singlePatch("/v1/pages/" + pageId);
        assertThat(objectMapper.readTree(req.getBodyAsString()).path("archived").asBoolean()).isTrue();
        // Both mappings cleaned up
        assertThat(countMappingsBySystem(entityId, "APPLE")).isZero();
        assertThat(countNotionMappings()).isZero();
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
    }

    @Test
    @DisplayName("A5 — CALENDAR_EVENT (ACTIVITY) created in Apple appears in Notion with Type=Activity (E2E-A5)")
    void calendar_event_created_in_apple_appears_as_activity_in_notion() throws Exception {
        String entityId = "EKEvent-" + UUID.randomUUID();
        String pageId = newPageId();
        stubCreateTaskPage(pageId);

        consumer.onMessage(calendarEvent(entityId, "Team sync A5", "HyperBrain"));
        outboxWorker.drainBatch();

        LoggedRequest req = singlePost("/v1/pages");
        JsonNode props = objectMapper.readTree(req.getBodyAsString()).path("properties");
        assertThat(props.path("Type").path("select").path("name").asText()).isEqualTo("Activity");
        assertThat(props.path("Name").path("title").get(0).path("text").path("content").asText())
            .isEqualTo("Team sync A5");
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
    }

    // ── Direction B: Notion → PG → Apple ──────────────────────────────────────

    @Test
    @DisplayName("B1 — Notion TASK created triggers REMINDER CREATED WriteCommand to Apple (E2E-B1)")
    void notion_task_created_triggers_apple_reminder() throws Exception {
        String pageId = newPageId();
        stubPatchPage(pageId);

        consumer.onMessage(notionAutomation(pageId, taskPage(pageId, "E2E task B1", "Not started", false)));
        outboxWorker.drainBatch();

        Message<String> msg = receiveOne(COMMANDS_QUEUE).orElseThrow(
            () -> new AssertionError("Expected WriteCommand on apple-commands.fifo but queue was empty"));
        JsonNode cmd = objectMapper.readTree(msg.getPayload());
        assertThat(cmd.path("command_type").asText()).isEqualTo("REMINDER");
        assertThat(cmd.path("operation").asText()).isEqualTo("CREATED");
        assertThat(cmd.path("payload").path("title").asText()).isEqualTo("E2E task B1");
        assertThat(cmd.path("entity_id").isNull()).isTrue();
        // Loop protection (RF-17): the NOTION-origin event never bounces back. The only Notion write
        // is the SYSTEM score reflection (#66a): the freshly persisted row's score reaches Notion via
        // a single update-only PATCH — never a create.
        assertThat(NOTION.findAll(postRequestedFor(urlPathEqualTo("/v1/pages")))).isEmpty();
        assertThat(NOTION.findAll(patchRequestedFor(urlEqualTo("/v1/pages/" + pageId)))).hasSize(1);
        // NOTION sync_mapping was created
        assertThat(countNotionMappings()).isEqualTo(1);
    }

    @Test
    @DisplayName("B2 — Notion TASK updated triggers REMINDER UPDATED WriteCommand to Apple (E2E-B2)")
    void notion_task_updated_triggers_apple_update() throws Exception {
        String pageId = newPageId();
        String appleEntityId = "EKReminder-" + UUID.randomUUID();
        UUID localId = UUID.randomUUID();
        insertExecutable(localId, "TASK", "TODO");
        insertNotionMapping(localId, pageId);
        insertAppleMapping(localId, appleEntityId);

        stubPatchPage(pageId);
        consumer.onMessage(notionAutomation(pageId, taskPage(pageId, "Updated title B2", "In progress", false)));
        outboxWorker.drainBatch();

        Message<String> msg = receiveOne(COMMANDS_QUEUE).orElseThrow(
            () -> new AssertionError("Expected UPDATED WriteCommand on apple-commands.fifo"));
        JsonNode cmd = objectMapper.readTree(msg.getPayload());
        assertThat(cmd.path("operation").asText()).isEqualTo("UPDATED");
        assertThat(cmd.path("entity_id").asText()).isEqualTo(appleEntityId);
        // RF-17: the NOTION event never bounces; the only Notion write is the SYSTEM score reflection
        assertThat(NOTION.findAll(postRequestedFor(urlPathEqualTo("/v1/pages")))).isEmpty();
    }

    @Test
    @DisplayName("B3 — Notion TASK completed triggers DELETED WriteCommand to Apple (E2E-B3)")
    void notion_task_completed_triggers_apple_deletion() throws Exception {
        String pageId = newPageId();
        String appleEntityId = "EKReminder-" + UUID.randomUUID();
        UUID localId = UUID.randomUUID();
        insertExecutable(localId, "TASK", "TODO");
        insertNotionMapping(localId, pageId);
        insertAppleMapping(localId, appleEntityId);

        stubPatchPage(pageId);
        consumer.onMessage(notionAutomation(pageId, taskPage(pageId, "Task B3", "Done", true)));
        outboxWorker.drainBatch();

        // DONE in PG removes the item from Apple rather than marking it completed:
        // the completed reminder would otherwise remain visible in the "Completed" section.
        Message<String> msg = receiveOne(COMMANDS_QUEUE).orElseThrow(
            () -> new AssertionError("Expected DELETED WriteCommand on apple-commands.fifo"));
        JsonNode cmd = objectMapper.readTree(msg.getPayload());
        assertThat(cmd.path("operation").asText()).isEqualTo("DELETED");
        assertThat(cmd.path("entity_id").asText()).isEqualTo(appleEntityId);
        assertThat(cmd.path("payload").isMissingNode() || cmd.path("payload").isNull()).isTrue();
        // RF-17: the NOTION event never bounces; the only Notion write is the SYSTEM score reflection
        assertThat(NOTION.findAll(postRequestedFor(urlPathEqualTo("/v1/pages")))).isEmpty();
    }

    @Test
    @DisplayName("B4 — Notion page archived triggers REMINDER DELETED WriteCommand to Apple (E2E-B4)")
    void notion_page_archived_triggers_apple_delete() throws Exception {
        String pageId = newPageId();
        String appleEntityId = "EKReminder-" + UUID.randomUUID();
        UUID localId = UUID.randomUUID();
        insertExecutable(localId, "TASK", "TODO");
        insertNotionMapping(localId, pageId);
        insertAppleMapping(localId, appleEntityId);

        consumer.onMessage(notionAutomation(pageId, trashedPage(pageId)));
        outboxWorker.drainBatch();

        Message<String> msg = receiveOne(COMMANDS_QUEUE).orElseThrow(
            () -> new AssertionError("Expected DELETED WriteCommand on apple-commands.fifo"));
        JsonNode cmd = objectMapper.readTree(msg.getPayload());
        assertThat(cmd.path("operation").asText()).isEqualTo("DELETED");
        assertThat(cmd.path("entity_id").asText()).isEqualTo(appleEntityId);
        assertThat(NOTION.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("B5 — AGENDA entity from Notion never generates WriteCommand to Apple (ADR-009, E2E-B5)")
    void agenda_entity_from_notion_never_triggers_apple_writeback() throws Exception {
        String pageId = newPageId();

        consumer.onMessage(notionAutomation(pageId,
            taskPage(pageId, "Agenda event B5", "Not started", false, "Agenda")));
        outboxWorker.drainBatch();

        // ADR-009: AGENDA is read-only from Apple's perspective — no WriteCommand
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
        // source_system=NOTION → loop protection blocks Notion write-back as well
        assertThat(NOTION.getAllServeEvents()).isEmpty();
    }

    // ── C: Transversal ────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 — Cycles: Notion cycle imported to core_cycle; Apple task with cycle propagates Cycle relation to Notion (E2E-C1)")
    void cycle_imported_from_notion_and_relation_propagated() throws Exception {
        // Import cycle from Notion
        String cyclePageId = newPageId();
        consumer.onMessage(notionAutomation(cyclePageId, cyclePage(cyclePageId, "Q3 Sprint", "MCI")));
        outboxWorker.drainBatch(); // no Apple propagation for CYCLE aggregate

        assertThat(jdbcTemplate.queryForObject("""
            SELECT c.name FROM core_cycle c JOIN sync_mappings m ON m.local_id = c.id
            WHERE m.external_id = ?
            """, String.class, cyclePageId)).isEqualTo("Q3 Sprint");

        // Apple reminder arrives and is linked to the cycle, then propagated to Notion
        String taskEntityId = "EKReminder-" + UUID.randomUUID();
        String taskPageId = newPageId();
        stubCreateTaskPage(taskPageId);
        consumer.onMessage(reminder("CREATED", taskEntityId, "Sprint task C1", false));
        UUID cycleId = jdbcTemplate.queryForObject("""
            SELECT c.id FROM core_cycle c JOIN sync_mappings m ON m.local_id = c.id
            WHERE m.external_id = ?
            """, UUID.class, cyclePageId);
        jdbcTemplate.update("""
            UPDATE core_executable SET cycle_id = ? WHERE name = 'Sprint task C1'
            """, cycleId);
        outboxWorker.drainBatch();

        LoggedRequest req = singlePost("/v1/pages");
        JsonNode cycleRelation = objectMapper.readTree(req.getBodyAsString())
            .path("properties").path("Cycle").path("relation");
        assertThat(cycleRelation.isArray()).isTrue();
        assertThat(cycleRelation.get(0).path("id").asText()).isEqualTo(cyclePageId);
    }

    @Test
    @DisplayName("C3 — No unprocessed outbox events and no stuck WriteCommands after a full A-direction run (E2E-C3)")
    void no_stuck_messages_after_pipeline_run() throws Exception {
        String entityId = "EKReminder-" + UUID.randomUUID();
        stubCreateTaskPage(newPageId());
        consumer.onMessage(reminder("CREATED", entityId, "Queue health check C3", false));
        outboxWorker.drainBatch();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE processed = false", Integer.class)).isZero();
        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
    }

    // ── WireMock stubs ────────────────────────────────────────────────────────

    private void stubCreateTaskPage(String pageId) {
        NOTION.stubFor(post(urlPathEqualTo("/v1/pages"))
            .withRequestBody(matchingJsonPath("$.parent.data_source_id", equalTo(TASKS_DS)))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"" + pageId + "\"}")));
    }

    private void stubPatchPage(String pageId) {
        NOTION.stubFor(patch(urlEqualTo("/v1/pages/" + pageId))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"page\",\"id\":\"" + pageId + "\"}")));
    }

    private LoggedRequest singlePost(String url) {
        List<LoggedRequest> requests = NOTION.findAll(postRequestedFor(urlEqualTo(url)));
        assertThat(requests).as("Expected exactly one POST %s", url).hasSize(1);
        return requests.get(0);
    }

    private LoggedRequest singlePatch(String url) {
        List<LoggedRequest> requests = NOTION.findAll(patchRequestedFor(urlEqualTo(url)));
        assertThat(requests).as("Expected exactly one PATCH %s", url).hasSize(1);
        return requests.get(0);
    }

    // ── Apple SyncEvent bodies ────────────────────────────────────────────────

    private static String reminder(String operation, String entityId, String title, boolean completed) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "REMINDER",
              "entity_id": "%s",
              "operation": "%s",
              "occurred_at": "2026-07-07T09:00:00-05:00",
              "payload": {
                "title": "%s",
                "notes": null,
                "due_date": "2026-07-10T09:00:00-05:00",
                "completed": %s,
                "priority": 0,
                "list_id": "EKCalendar-hb",
                "list_name": "HyperBrain",
                "alarms": []
              }
            }
            """.formatted(UUID.randomUUID(), entityId, operation, title, completed);
    }

    private static String reminderDeleted(String entityId) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "REMINDER",
              "entity_id": "%s",
              "operation": "DELETED",
              "occurred_at": "2026-07-07T09:30:00-05:00",
              "payload": null
            }
            """.formatted(UUID.randomUUID(), entityId);
    }

    private static String calendarEvent(String entityId, String title, String calendarName) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "CALENDAR_EVENT",
              "entity_id": "%s",
              "operation": "CREATED",
              "occurred_at": "2026-07-07T09:00:00-05:00",
              "payload": {
                "title": "%s",
                "start_time": "2026-07-10T09:00:00-05:00",
                "end_time": "2026-07-10T10:00:00-05:00",
                "all_day": false,
                "notes": null,
                "calendar_id": "EKCalendar-work",
                "calendar_name": "%s",
                "location": null,
                "alarms": []
              }
            }
            """.formatted(UUID.randomUUID(), entityId, title, calendarName);
    }

    // ── Notion automation envelope bodies ─────────────────────────────────────

    private static String notionAutomation(String pageId, String pageJson) {
        return """
            {"source_system":"NOTION","message_id":"%s","delivery_channel":"automation",
             "timestamp":"2026-07-07T09:00:00Z",
             "payload":{"source":{"type":"automation","automation_id":"auto-e2e"},"data":%s}}
            """.formatted(UUID.randomUUID(), pageJson);
    }

    private String taskPage(String pageId, String name, String status, boolean complete) {
        return taskPage(pageId, name, status, complete, "Task");
    }

    private String taskPage(String pageId, String name, String status, boolean complete, String type) {
        return """
            {"object":"page","id":"%s","last_edited_time":"2026-07-07T09:00:00.000Z",
             "archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"%s"}},
               "Complete":{"type":"checkbox","checkbox":%s},
               "Type":{"type":"select","select":{"name":"%s"}}}}
            """.formatted(pageId, TASKS_DB, name, status, complete, type);
    }

    private String trashedPage(String pageId) {
        return """
            {"object":"page","id":"%s","last_edited_time":"2026-07-07T09:30:00.000Z",
             "archived":false,"in_trash":true,
             "parent":{"type":"database_id","database_id":"%s"},"properties":{}}
            """.formatted(pageId, TASKS_DB);
    }

    private String cyclePage(String pageId, String name, String type) {
        return """
            {"object":"page","id":"%s","last_edited_time":"2026-07-07T09:00:00.000Z",
             "archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Type":{"type":"select","select":{"name":"%s"}},
               "Date":{"type":"date","date":{"start":"2026-07-01","end":"2026-07-14"}},
               "Inactive":{"type":"checkbox","checkbox":false}}}
            """.formatted(pageId, CYCLES_DB, name, type);
    }

    // ── JDBC helpers ──────────────────────────────────────────────────────────

    private void insertExecutable(UUID id, String type, String status) {
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, start_time, end_time, source_calendar)
            VALUES (?, ?, 'E2E fixture', ?, ?, now(), now() + interval '1 hour', 'HyperBrain')
            """, id, DataFixture.SYSTEM_USER_ID, type, status);
    }

    private void insertNotionMapping(UUID localId, String pageId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'NOTION', ?, 'fixture-checksum', 'SYNCED', '2026-01-01 00:00:00+00'::timestamptz)
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, pageId);
    }

    private void insertAppleMapping(UUID localId, String externalId) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'APPLE', ?, 'fixture-checksum', 'SYNCED', now())
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, externalId);
    }

    private int countMappingsBySystem(String externalId, String system) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system = ? AND external_id = ?",
            Integer.class, system, externalId);
        return n == null ? 0 : n;
    }

    private int countNotionMappings() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system = 'NOTION'", Integer.class);
        return n == null ? 0 : n;
    }

    // ── SQS helpers ───────────────────────────────────────────────────────────

    private Optional<Message<String>> receiveOne(String queue) {
        return sqsTemplate.receive(from -> from
            .queue(queue)
            .pollTimeout(Duration.ofSeconds(5)), String.class);
    }

    private void drainQueue(String queue) {
        while (receiveOne(queue).isPresent()) {
            // discard until empty
        }
    }

    private static String newPageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
