package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.SyncEventIngestionService;
import com.hyperbrain.sync.infrastructure.NotionEnvelopeNormalizer;
import com.hyperbrain.sync.infrastructure.SqsConsumer;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the ADR-039 FAILED matrix and Status-first merge through the Notion
 * inbound pipeline against a real PostgreSQL: {@code Status=Failed} lands as {@code FAILED}
 * (first-class, no longer collapsed to DONE); a recurring executable closing as {@code FAILED}
 * clones the next occurrence (DR-04 never-miss-twice) and resets the streak, while a
 * {@code DONE} closure extends it.
 */
@IntegrationTest
@DisplayName("FAILED matrix + Status-first merge (ADR-039)")
class FailedMatrixIT {

    private static final String TASKS_DB = "1bf8bc9c5d91812b8c97e5e6450858aa";
    private static final String TASKS_DS = "tasksds0000000000000000000000001";

    @DynamicPropertySource
    static void notionProperties(DynamicPropertyRegistry registry) {
        registry.add("app.sync.notion.enabled", () -> "true");
        registry.add("app.sync.notion.base-url", () -> "http://localhost:1");
        registry.add("app.sync.notion.token", () -> "test-token");
        registry.add("app.sync.notion.tasks-data-source-id", () -> TASKS_DS);
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
        jdbcTemplate.update("UPDATE core_executable SET parent_id = NULL, container_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("Status=Failed lands as FAILED (first-class), not DONE")
    void status_failed_persists_failed() {
        String page = newPageId();
        deliver(page, taskPage(page, "Habit", "Task", "Not started", false, null,
            "2026-08-06T09:00:00.000Z"));
        deliver(page, taskPage(page, "Habit", "Task", "Failed", false, null,
            "2026-08-06T10:00:00.000Z"));

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM core_executable WHERE id = ?", String.class, localId(page));
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a recurring executable closing as FAILED clones the next occurrence and resets the streak")
    void failed_recurrence_clones_and_resets_streak() {
        String page = newPageId();
        // A daily habit with a prior streak, currently in progress.
        deliver(page, taskPage(page, "Meditate", "Habit", "In progress", false, 1.0,
            "2026-08-06T09:00:00.000Z"));
        UUID id = localId(page);
        jdbcTemplate.update(
            "UPDATE core_executable SET current_streak = 5, best_streak = 5, "
                + "start_time = '2026-08-06T06:00:00Z' WHERE id = ?", id);

        // The user marks it Failed (a sanctioned miss).
        deliver(page, taskPage(page, "Meditate", "Habit", "Failed", false, 1.0,
            "2026-08-06T22:00:00.000Z"));

        Map<String, Object> original = jdbcTemplate.queryForMap(
            "SELECT status, current_streak, best_streak, last_completed_at "
                + "FROM core_executable WHERE id = ?", id);
        assertThat(original.get("status")).isEqualTo("FAILED");
        assertThat(original.get("current_streak")).isEqualTo(0);   // reset on FAILED
        assertThat(original.get("best_streak")).isEqualTo(5);      // history survives
        assertThat(original.get("last_completed_at")).isNotNull(); // stamped even on a miss

        // DR-04 cloned the next occurrence (never miss twice), inheriting the streak.
        List<Map<String, Object>> clones = jdbcTemplate.queryForList(
            "SELECT status, current_streak FROM core_executable WHERE id <> ? AND name = 'Meditate'",
            id);
        assertThat(clones).hasSize(1);
        assertThat(clones.get(0).get("status")).isEqualTo("TODO");
        assertThat(clones.get(0).get("current_streak")).isEqualTo(0);
    }

    @Test
    @DisplayName("a DONE closure extends the streak")
    void done_extends_streak() {
        String page = newPageId();
        deliver(page, taskPage(page, "Read", "Habit", "In progress", false, 1.0,
            "2026-08-06T09:00:00.000Z"));
        UUID id = localId(page);
        jdbcTemplate.update(
            "UPDATE core_executable SET current_streak = 2, best_streak = 4 WHERE id = ?", id);

        deliver(page, taskPage(page, "Read", "Habit", "Done", true, 1.0,
            "2026-08-06T21:00:00.000Z"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT status, current_streak, best_streak FROM core_executable WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(row.get("current_streak")).isEqualTo(3);
        assertThat(row.get("best_streak")).isEqualTo(4);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void deliver(String pageId, String pageJson) {
        consumer.onMessage("""
            {"source_system":"NOTION","message_id":"%s","delivery_channel":"automation",
             "timestamp":"2026-08-06T10:00:00Z",
             "payload":{"source":{"type":"automation","automation_id":"auto-1"},"data":%s}}
            """.formatted(UUID.randomUUID(), pageJson));
    }

    private String taskPage(String pageId, String name, String type, String status,
                            boolean complete, Double frequency, String lastEditedTime) {
        String freqProp = frequency != null
            ? ",\"Frequency\":{\"type\":\"number\",\"number\":" + frequency + "}"
            : "";
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"%s"}},
               "Complete":{"type":"checkbox","checkbox":%s},
               "Type":{"type":"select","select":{"name":"%s"}}%s}}
            """.formatted(pageId, lastEditedTime, TASKS_DB, name, status, complete, type, freqProp);
    }

    private UUID localId(String pageId) {
        return jdbcTemplate.queryForObject(
            "SELECT local_id FROM sync_mappings WHERE external_system = 'NOTION' AND external_id = ?",
            UUID.class, pageId);
    }

    private static String newPageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
