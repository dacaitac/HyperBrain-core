package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hyperbrain.shared.outbox.OutboxWorker;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
 * End-to-end pipeline tests for the Notion write-back (HU-10, CA-11): a {@code core_executable}
 * or {@code core_cycle} change drained from the outbox must produce the right REST calls against
 * a stubbed Notion API (WireMock) and close the {@code sync_mappings} synchronously (ADR-011 —
 * no result queue), while {@code source_system=NOTION} changes never call the API.
 */
@IntegrationTest
@DisplayName("Notion write-back — outbound sync pipeline (HU-10)")
class NotionWriteBackIT {

    private static final String TASKS_DS = "tasksds0000000000000000000000001";
    private static final String CYCLES_DS = "cyclesds000000000000000000000001";

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
        // Keep retries fast and deterministic in tests.
        registry.add("app.sync.notion.min-request-interval-ms", () -> "0");
        registry.add("app.sync.notion.backoff-base-ms", () -> "10");
        registry.add("app.sync.notion.max-attempts", () -> "3");
    }

    @AfterAll
    static void stopServer() {
        NOTION.stop();
    }

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        NOTION.resetAll();
    }

    @Test
    @DisplayName("CREATE: posts the full attribute mapping and persists the page_id in sync_mappings (CA-3)")
    void create_posts_page_and_persists_mapping() throws Exception {
        // Given an unmapped executable with every mapped attribute populated
        UUID localId = insertExecutable("TASK", "TODO");
        insertProfile(localId, 5, 1, 4);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM");
        String pageId = "2fa8bc9c-5d91-81ba-b3c9-f2a27fa48cc9";
        stubCreatePage(TASKS_DS, pageId);

        // When the outbox drains
        outboxWorker.drainBatch();

        // Then the POST carries the attribute-by-attribute mapping of issue #15
        LoggedRequest request = singleRequest(
            postRequestedFor(urlEqualTo("/v1/pages"))
                .withHeader("Authorization", WireMock.equalTo("Bearer test-token"))
                .withHeader("Notion-Version", WireMock.equalTo("2025-09-03")));
        JsonNode body = objectMapper.readTree(request.getBodyAsString());
        assertThat(body.path("parent").path("data_source_id").asText()).isEqualTo(TASKS_DS);
        JsonNode props = body.path("properties");
        assertThat(props.path("Name").path("title").get(0).path("text").path("content").asText())
            .isEqualTo("Write tests");
        assertThat(props.path("Description").path("rich_text").get(0).path("text").path("content").asText())
            .isEqualTo("Detailed description");
        assertThat(props.path("Status").path("status").path("name").asText()).isEqualTo("Not started");
        assertThat(props.path("Complete").path("checkbox").asBoolean()).isFalse();
        assertThat(props.path("Type").path("select").path("name").asText()).isEqualTo("Task");
        assertThat(props.path("Priority Score").path("number").asDouble()).isEqualTo(0.8);
        assertThat(props.path("Urgence").path("number").asDouble()).isEqualTo(0.6);
        assertThat(props.path("Effort").path("number").asDouble()).isEqualTo(2.5);
        assertThat(props.path("Energy").path("select").path("name").asText()).isEqualTo("Intenso");
        assertThat(props.path("Mental Load").path("select").path("name").asText()).isEqualTo("Rutinario");
        assertThat(props.path("Impact").path("select").path("name").asText()).isEqualTo("Alto");
        assertThat(props.path("Date").path("date").path("start").asText()).isNotBlank();

        // And the sync_mapping closes synchronously with the normalized page_id (no result queue)
        Map<String, Object> mapping = jdbcTemplate.queryForMap(
            "SELECT external_id, last_known_checksum, sync_status, last_synced_at "
                + "FROM sync_mappings WHERE external_system = 'NOTION' AND local_id = ?", localId);
        assertThat(mapping.get("external_id")).isEqualTo("2fa8bc9c5d9181bab3c9f2a27fa48cc9");
        assertThat((String) mapping.get("last_known_checksum")).hasSize(64);
        assertThat(mapping.get("sync_status")).isEqualTo("SYNCED");
        assertThat(mapping.get("last_synced_at")).isNotNull();
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("UPDATE: patches the mapped page and refreshes checksum + last_synced_at (CA-4, CA-7)")
    void update_patches_mapped_page() {
        // Given a mapped executable
        UUID localId = insertExecutable("ACTIVITY", "IN_PROGRESS");
        String externalId = "page00000000000000000000000000aa";
        insertMapping(localId, externalId, "previous-checksum");
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableUpdatedEvent", "APPLE");
        stubPatchPage(externalId);

        // When
        outboxWorker.drainBatch();

        // Then only the mapped page is patched with the affected properties
        singleRequest(patchRequestedFor(urlEqualTo("/v1/pages/" + externalId))
            .withRequestBody(matchingJsonPath("$.properties.Status.status.name",
                WireMock.equalTo("In progress"))));
        String checksum = jdbcTemplate.queryForObject(
            "SELECT last_known_checksum FROM sync_mappings WHERE external_id = ?",
            String.class, externalId);
        assertThat(checksum).hasSize(64).isNotEqualTo("previous-checksum");
    }

    @Test
    @DisplayName("closure: TaskCompletedEvent writes Status=Done and Complete=true")
    void completion_marks_done_and_complete() {
        // Given a mapped executable completed in iOS
        UUID localId = insertExecutable("TASK", "DONE");
        String externalId = "page00000000000000000000000000bb";
        insertMapping(localId, externalId, null);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "TaskCompletedEvent", "APPLE");
        stubPatchPage(externalId);

        // When
        outboxWorker.drainBatch();

        // Then
        singleRequest(patchRequestedFor(urlEqualTo("/v1/pages/" + externalId))
            .withRequestBody(matchingJsonPath("$.properties.Status.status.name",
                WireMock.equalTo("Done")))
            .withRequestBody(matchingJsonPath("$.properties.Complete.checkbox",
                WireMock.equalTo("true"))));
    }

    @Test
    @DisplayName("DELETE: archives the page and removes the sync_mapping (CA-5)")
    void delete_archives_page_and_removes_mapping() {
        // Given a mapping whose executable was already deleted locally
        UUID localId = UUID.randomUUID();
        String externalId = "page00000000000000000000000000cc";
        insertMapping(localId, externalId, null);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableDeletedEvent", "SYSTEM");
        stubPatchPage(externalId);

        // When
        outboxWorker.drainBatch();

        // Then
        singleRequest(patchRequestedFor(urlEqualTo("/v1/pages/" + externalId))
            .withRequestBody(matchingJsonPath("$.archived", WireMock.equalTo("true"))));
        Integer mappings = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_id = ?", Integer.class, externalId);
        assertThat(mappings).isZero();
    }

    @Test
    @DisplayName("Cycles: a task with an unmapped cycle creates the cycle page first and links the relation (CA-6)")
    void unmapped_cycle_is_created_before_the_task() throws Exception {
        // Given an executable owned by a cycle that has no Notion page yet
        UUID cycleId = insertCycle("Sprint 2", "MCI", "ACTIVE");
        UUID localId = insertExecutable("TASK", "TODO", cycleId);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM");
        String cyclePageId = "3f000000-0000-0000-0000-00000000cccc".replace("-", "");
        stubCreatePage(CYCLES_DS, cyclePageId);
        stubCreatePage(TASKS_DS, "4f000000000000000000000000000000");

        // When
        outboxWorker.drainBatch();

        // Then the cycle page carries the core_cycle mapping (Name/Type/Date/Inactive)
        LoggedRequest cycleRequest = singleRequest(postRequestedFor(urlEqualTo("/v1/pages"))
            .withRequestBody(matchingJsonPath("$.parent.data_source_id",
                WireMock.equalTo(CYCLES_DS))));
        JsonNode cycleProps = objectMapper.readTree(cycleRequest.getBodyAsString()).path("properties");
        assertThat(cycleProps.path("Name").path("title").get(0).path("text").path("content").asText())
            .isEqualTo("Sprint 2");
        assertThat(cycleProps.path("Type").path("select").path("name").asText()).isEqualTo("MCI");
        assertThat(cycleProps.path("Inactive").path("checkbox").asBoolean()).isFalse();

        // And the task page links the Cycle relation to the fresh cycle page
        LoggedRequest taskRequest = singleRequest(postRequestedFor(urlEqualTo("/v1/pages"))
            .withRequestBody(matchingJsonPath("$.parent.data_source_id",
                WireMock.equalTo(TASKS_DS))));
        JsonNode taskProps = objectMapper.readTree(taskRequest.getBodyAsString()).path("properties");
        assertThat(taskProps.path("Cycle").path("relation").get(0).path("id").asText())
            .isEqualTo(cyclePageId);

        // And both entities closed their mappings
        Integer mappings = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system = 'NOTION'", Integer.class);
        assertThat(mappings).isEqualTo(2);
    }

    @Test
    @DisplayName("loop protection: source_system=NOTION never calls the Notion API (CA-2, CA-11)")
    void notion_sourced_change_is_not_written_back() {
        // Given a Notion-originated change
        UUID localId = insertExecutable("TASK", "TODO");
        insertMapping(localId, "page00000000000000000000000000dd", null);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableUpdatedEvent", "NOTION");

        // When
        outboxWorker.drainBatch();

        // Then no HTTP call reaches the stub and the event completes normally
        assertThat(NOTION.getAllServeEvents()).isEmpty();
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("AGENDA entities do propagate to Notion (ADR-009 applies only to Apple)")
    void agenda_propagates_to_notion() {
        // Given an AGENDA executable (read-only towards Apple, visible in Notion)
        UUID localId = insertExecutable("AGENDA", "TODO");
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableCreatedEvent", "APPLE");
        stubCreatePage(TASKS_DS, "5f000000000000000000000000000000");

        // When
        outboxWorker.drainBatch();

        // Then
        singleRequest(postRequestedFor(urlEqualTo("/v1/pages"))
            .withRequestBody(matchingJsonPath("$.properties.Type.select.name",
                WireMock.equalTo("Agenda"))));
    }

    @Test
    @DisplayName("page deleted manually in Notion: 404 on UPDATE repairs the mapping (CA-15)")
    void manual_deletion_in_notion_repairs_mapping() {
        // Given a mapping pointing at a page that no longer exists
        UUID localId = insertExecutable("TASK", "TODO");
        String staleId = "page00000000000000000000000000ee";
        insertMapping(localId, staleId, null);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM");
        NOTION.stubFor(WireMock.patch(urlEqualTo("/v1/pages/" + staleId))
            .willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\"}")));
        stubCreatePage(TASKS_DS, "6f000000000000000000000000000000");

        // When
        outboxWorker.drainBatch();

        // Then the stale mapping is replaced by one pointing at the recreated page
        List<String> externalIds = jdbcTemplate.queryForList(
            "SELECT external_id FROM sync_mappings WHERE local_id = ?", String.class, localId);
        assertThat(externalIds).containsExactly("6f000000000000000000000000000000");
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("rate limit: a 429 with Retry-After is retried and succeeds (CA-8)")
    void rate_limited_call_is_retried() {
        // Given the stub rejects the first PATCH with 429 and accepts the second
        UUID localId = insertExecutable("TASK", "TODO");
        String externalId = "page00000000000000000000000000ff";
        insertMapping(localId, externalId, null);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM");
        NOTION.stubFor(WireMock.patch(urlEqualTo("/v1/pages/" + externalId))
            .inScenario("rate-limit").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("recovered"));
        NOTION.stubFor(WireMock.patch(urlEqualTo("/v1/pages/" + externalId))
            .inScenario("rate-limit").whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        // When
        outboxWorker.drainBatch();

        // Then the write eventually lands and the event completes
        NOTION.verify(2, patchRequestedFor(urlEqualTo("/v1/pages/" + externalId)));
        assertThat(unprocessedEvents()).isZero();
    }

    @Test
    @DisplayName("Notion unavailable: persistent 5xx leaves the event unprocessed and marks the mapping ERROR (CA-13)")
    void persistent_server_error_leaves_event_for_retry() {
        // Given the Notion API answers 503 to everything
        UUID localId = insertExecutable("TASK", "TODO");
        String externalId = "page000000000000000000000000abcd";
        insertMapping(localId, externalId, null);
        insertOutboxEvent(localId, "CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM");
        NOTION.stubFor(WireMock.patch(urlEqualTo("/v1/pages/" + externalId))
            .willReturn(aResponse().withStatus(503)));

        // When
        outboxWorker.drainBatch();

        // Then all attempts were exhausted, the event stays queued and the failure is visible
        NOTION.verify(3, patchRequestedFor(urlEqualTo("/v1/pages/" + externalId)));
        assertThat(unprocessedEvents()).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
            "SELECT sync_status FROM sync_mappings WHERE external_id = ?", String.class, externalId);
        assertThat(status).isEqualTo("ERROR");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubCreatePage(String dataSourceId, String pageId) {
        NOTION.stubFor(post(urlPathEqualTo("/v1/pages"))
            .withRequestBody(matchingJsonPath("$.parent.data_source_id",
                WireMock.equalTo(dataSourceId)))
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

    private UUID insertExecutable(String type, String status) {
        return insertExecutable(type, status, null);
    }

    private UUID insertExecutable(String type, String status, UUID cycleId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, cycle_id, name, description, type, status,
                 priority_score, urgency_score, effort_score, start_time, end_time)
            VALUES (?, ?, ?, 'Write tests', 'Detailed description', ?, ?,
                    0.8, 0.6, 2.5, now(), now() + interval '1 hour')
            """, id, DataFixture.SYSTEM_USER_ID, cycleId, type, status);
        return id;
    }

    private void insertProfile(UUID executableId, int energyDrain, int mentalLoad, int impact) {
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, energy_drain, mental_load, impact)
            VALUES (?, ?, ?, ?)
            """, executableId, energyDrain, mentalLoad, impact);
    }

    private UUID insertCycle(String name, String type, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, start_date, end_date)
            VALUES (?, ?, ?, ?, ?, DATE '2026-07-01', DATE '2026-07-14')
            """, id, DataFixture.SYSTEM_USER_ID, name, type, status);
        return id;
    }

    private void insertMapping(UUID localId, String externalId, String checksum) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'NOTION', ?, ?, 'SYNCED', now())
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, externalId, checksum);
    }

    private void insertOutboxEvent(UUID localId, String aggregateType, String eventType,
                                   String sourceSystem) {
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, ?, ?, ?, '{}'::jsonb, ?, now())
            """, UUID.randomUUID(), aggregateType, localId.toString(), eventType, sourceSystem);
    }

    private int unprocessedEvents() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE processed = false", Integer.class);
        return count == null ? 0 : count;
    }
}
