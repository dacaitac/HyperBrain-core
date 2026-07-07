package com.hyperbrain.sync;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.NotionBackfillService;
import com.hyperbrain.sync.application.SyncOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the initial Notion import (HU-14 CA-8): both databases are queried page by page
 * and persisted through the same upsert path as the webhooks, so re-running the backfill —
 * or overlapping it with webhook deliveries of the same pages (CA-28) — never duplicates.
 */
@IntegrationTest
@DisplayName("Notion backfill — initial import of Tasks and Cycles (HU-14 CA-8)")
class NotionBackfillIT {

    private static final String TASKS_DS = "1bf8bc9c5d918171b7ea000b7e326082";
    private static final String CYCLES_DS = "1bf8bc9c5d9181e78737000b45812f45";
    private static final String CYCLE_PAGE = "aaaa0000000000000000000000000001";
    private static final String TASK_PAGE_1 = "bbbb0000000000000000000000000001";
    private static final String TASK_PAGE_2 = "bbbb0000000000000000000000000002";

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
        registry.add("app.sync.notion.min-request-interval-ms", () -> "0");
        registry.add("app.sync.notion.backoff-base-ms", () -> "10");
        registry.add("app.sync.notion.max-attempts", () -> "2");
    }

    @AfterAll
    static void stopServer() {
        NOTION.stop();
    }

    @Autowired private NotionBackfillService backfillService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanState() throws Exception {
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
        stubQueries();
    }

    @Test
    @DisplayName("imports cycles first, then tasks with their relations resolved; pagination followed to the end")
    void imports_both_databases() {
        // When
        NotionBackfillService.BackfillSummary summary = backfillService.backfill();

        // Then
        assertThat(summary.cycles()).containsEntry(SyncOutcome.CREATED, 1);
        assertThat(summary.tasks()).containsEntry(SyncOutcome.CREATED, 2);

        Map<String, Object> task = jdbcTemplate.queryForMap("""
            SELECT e.name, c.name AS cycle_name
            FROM core_executable e
            LEFT JOIN core_cycle c ON c.id = e.cycle_id
            JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_id = ?
            """, TASK_PAGE_1);
        assertThat(task.get("name")).isEqualTo("Backfilled task");
        assertThat(task.get("cycle_name")).isEqualTo("Sprint 2");

        // The second task arrived through the paginated second query page
        Integer mappings = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system = 'NOTION'", Integer.class);
        assertThat(mappings).isEqualTo(3);
    }

    @Test
    @DisplayName("CA-8/CA-28: re-running the backfill is idempotent — unchanged pages are discarded by the monotonicity guard")
    void rerun_is_idempotent() {
        // Given a completed first run
        backfillService.backfill();
        Integer outboxAfterFirst = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events", Integer.class);

        // When
        NotionBackfillService.BackfillSummary second = backfillService.backfill();

        // Then nothing was created or updated again: the pages carry the same last_edited_time
        // that the first run stored in last_synced_at, so CA-29 discards them before the
        // checksum even runs (either guard yields the same convergence)
        assertThat(second.cycles()).containsEntry(SyncOutcome.SKIPPED_STALE, 1);
        assertThat(second.tasks()).containsEntry(SyncOutcome.SKIPPED_STALE, 2);
        Integer executables = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable", Integer.class);
        assertThat(executables).isEqualTo(2);
        Integer outboxAfterSecond = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outboxAfterSecond).isEqualTo(outboxAfterFirst);
    }

    private void stubQueries() {
        // Cycles: single page of results
        NOTION.stubFor(post(urlEqualTo("/v1/data_sources/" + CYCLES_DS + "/query"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"object":"list","results":[%s],"has_more":false,"next_cursor":null}
                    """.formatted(cyclePage()))));
        // Tasks: two pages to exercise pagination
        NOTION.stubFor(post(urlEqualTo("/v1/data_sources/" + TASKS_DS + "/query"))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*start_cursor.*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"object":"list","results":[%s],"has_more":true,"next_cursor":"cursor-2"}
                    """.formatted(taskPage(TASK_PAGE_1, "Backfilled task", CYCLE_PAGE)))));
        NOTION.stubFor(post(urlEqualTo("/v1/data_sources/" + TASKS_DS + "/query"))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                "$.start_cursor", com.github.tomakehurst.wiremock.client.WireMock.equalTo("cursor-2")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"object":"list","results":[%s],"has_more":false,"next_cursor":null}
                    """.formatted(taskPage(TASK_PAGE_2, "Second page task", null)))));
    }

    private static String cyclePage() {
        return """
            {"object":"page","id":"%s","last_edited_time":"2026-07-07T14:00:00.000Z",
             "archived":false,"in_trash":false,
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"Sprint 2"}]},
               "Type":{"type":"select","select":{"name":"MCI"}},
               "Date":{"type":"date","date":{"start":"2026-07-01","end":"2026-07-14"}},
               "Inactive":{"type":"checkbox","checkbox":false}}}
            """.formatted(CYCLE_PAGE);
    }

    private static String taskPage(String pageId, String name, String cyclePageId) {
        String cycleRelation = cyclePageId != null
            ? ",\"Cycle\":{\"type\":\"relation\",\"relation\":[{\"id\":\"" + cyclePageId + "\"}]}"
            : "";
        return """
            {"object":"page","id":"%s","last_edited_time":"2026-07-07T15:00:00.000Z",
             "archived":false,"in_trash":false,
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"Not started"}},
               "Complete":{"type":"checkbox","checkbox":false},
               "Type":{"type":"select","select":{"name":"Task"}}%s}}
            """.formatted(pageId, name, cycleRelation);
    }
}
