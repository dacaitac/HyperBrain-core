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

import java.time.OffsetDateTime;
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
@DisplayName("ADR-039 Notion inbound — FAILED matrix, Status-first, containment via Parent Task")
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

    @Test
    @DisplayName("a task whose Parent Task points at a TIME_BLOCK gets container_block_id set and the block's date + cycle hard-copied")
    void parent_task_to_block_assigns_container_and_hard_copies() {
        UUID userId = DataFixture.SYSTEM_USER_ID;
        // A cycle and a live TIME_BLOCK executable already mirrored to a Notion page.
        UUID cycleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO core_cycle (id, user_id, name, type, status) VALUES (?, ?, 'C', 'PROJECT', 'ACTIVE')",
            cycleId, userId);
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, cycle_id, name, type, status, origin, start_time, end_time, system_generated)
            VALUES (?, ?, ?, 'Morning block', 'TIME_BLOCK', 'PLANNED', 'PLANNER',
                    '2026-08-06T09:00:00Z', '2026-08-06T10:00:00Z', false)
            """, blockId, userId, cycleId);
        String blockPage = newPageId();
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id, sync_status)
            VALUES (?, ?, ?, 'NOTION', ?, 'SYNCED')
            """, UUID.randomUUID(), userId, blockId, blockPage);

        // A Task page dragged into that block in Notion: its Parent Task points at the block page.
        String taskPage = newPageId();
        deliver(taskPage, taskPageWithParent(taskPage, "Write report", blockPage,
            "2026-08-06T11:00:00.000Z"));

        UUID taskId = localId(taskPage);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT container_block_id, start_time, end_time, cycle_id FROM core_executable WHERE id = ?",
            taskId);
        assertThat(row.get("container_block_id")).isEqualTo(blockId);
        // Hard copy: a reminder-type task takes the block's start + cycle, end cleared by DR-01.
        assertThat(((OffsetDateTime) jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, taskId))
            .toInstant())
            .isEqualTo(OffsetDateTime.parse("2026-08-06T09:00:00Z").toInstant());
        assertThat(row.get("end_time")).isNull();
        assertThat(row.get("cycle_id")).isEqualTo(cycleId);
    }

    @Test
    @DisplayName("an ACTIVITY whose Parent Task points at a TIME_BLOCK is NOT contained (it is a calendar event already) — container stays null")
    void activity_pointed_at_block_is_not_contained() {
        UUID userId = DataFixture.SYSTEM_USER_ID;
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, origin, start_time, end_time, system_generated)
            VALUES (?, ?, 'Morning block', 'TIME_BLOCK', 'PLANNED', 'PLANNER',
                    '2026-08-06T09:00:00Z', '2026-08-06T10:00:00Z', false)
            """, blockId, userId);
        String blockPage = newPageId();
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id, sync_status)
            VALUES (?, ?, ?, 'NOTION', ?, 'SYNCED')
            """, UUID.randomUUID(), userId, blockId, blockPage);

        // An ACTIVITY page whose Parent Task points at the block (the user dropped it in).
        String activityPage = newPageId();
        deliver(activityPage, activityPageWithParent(activityPage, "Doctor appointment", blockPage,
            "2026-08-06T11:00:00.000Z"));

        UUID activityId = localId(activityPage);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT type, container_block_id FROM core_executable WHERE id = ?", activityId);
        assertThat(row.get("type")).isEqualTo("ACTIVITY");
        // The calendar-event type is never contained (ADR-039, core#61).
        assertThat(row.get("container_block_id")).isNull();
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

    private String taskPageWithParent(String pageId, String name, String parentPageId,
                                      String lastEditedTime) {
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"Not started"}},
               "Complete":{"type":"checkbox","checkbox":false},
               "Type":{"type":"select","select":{"name":"Task"}},
               "Parent Task":{"type":"relation","relation":[{"id":"%s"}]}}}
            """.formatted(pageId, lastEditedTime, TASKS_DB, name, parentPageId);
    }

    private String activityPageWithParent(String pageId, String name, String parentPageId,
                                          String lastEditedTime) {
        return """
            {"object":"page","id":"%s","last_edited_time":"%s","archived":false,"in_trash":false,
             "parent":{"type":"database_id","database_id":"%s"},
             "properties":{
               "Name":{"type":"title","title":[{"plain_text":"%s"}]},
               "Status":{"type":"status","status":{"name":"Not started"}},
               "Complete":{"type":"checkbox","checkbox":false},
               "Type":{"type":"select","select":{"name":"Activity"}},
               "Date":{"type":"date","date":{"start":"2026-08-07T14:00:00.000-05:00","end":"2026-08-07T15:00:00.000-05:00"}},
               "Parent Task":{"type":"relation","relation":[{"id":"%s"}]}}}
            """.formatted(pageId, lastEditedTime, TASKS_DB, name, parentPageId);
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
