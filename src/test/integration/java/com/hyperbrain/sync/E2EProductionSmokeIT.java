package com.hyperbrain.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production E2E smoke tests for the full iOS ↔ PG ↔ Notion transversal pipeline (HU-15 CA-2).
 *
 * <p>These tests drive the <b>live</b> SentinelAPI (Apple side) and the <b>real</b> Notion API,
 * asserting that CRUD operations in one system propagate end-to-end to the other. They are
 * disabled unless {@code E2E_PROD_SMOKE_ENABLED=true} and require:
 *
 * <ul>
 *   <li>{@code SENTINEL_API_BASE_URL} — e.g. {@code http://100.74.180.105:8080}
 *   <li>{@code NOTION_API_TOKEN} — Notion integration bearer token
 *   <li>{@code NOTION_TASKS_DATABASE_ID} — ID of the Tasks database (with or without dashes)
 * </ul>
 *
 * <p><b>Direction A</b> (Apple → Notion): CRUD operations via {@code POST/PUT/DELETE /reminders}
 * on SentinelAPI are verified to surface in the Notion Tasks database by polling the Notion API.
 *
 * <p><b>Direction B</b> (Notion → Apple): page operations via the Notion REST API trigger the
 * webhook pipeline (Lambda → SQS → core → apple-commands.fifo → SentinelAPI) and are verified
 * by polling {@code GET /reminders} on SentinelAPI.
 *
 * <p>Poll budgets: 120 s for direction A (no webhook hop), 180 s for direction B (webhook delay +
 * SentinelAPI consumer). Latency is printed to standard output for CA-4 evidence.
 *
 * <p>All created entities are cleaned up in {@code finally} blocks. Tests create reminders in
 * iOS Reminders — run outside business hours to avoid cluttering Daniel's workflow lists.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "E2E_PROD_SMOKE_ENABLED", matches = "true")
class E2EProductionSmokeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_A = Duration.ofSeconds(120);
    private static final Duration POLL_B = Duration.ofSeconds(180);
    private static final long POLL_INTERVAL_MS = 4_000;
    private static final String NOTION_VERSION = "2022-06-28";

    private final String sentinelBase = System.getenv("SENTINEL_API_BASE_URL");
    private final String notionToken  = System.getenv("NOTION_API_TOKEN");
    private final String notionTasksDb = System.getenv("NOTION_TASKS_DATABASE_ID");
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // ── Direction A: Apple → PG → Notion ──────────────────────────────────────

    @Test
    void a1_reminder_created_in_apple_appears_in_notion() throws Exception {
        String title = "e2e-a1-" + System.currentTimeMillis();
        String reminderId = createReminder(title, false);
        Instant t0 = Instant.now();
        try {
            String pageId = awaitNotionPageByTitle(title, POLL_A);
            logLatency("A1 Apple CREATED → Notion page", t0);
            JsonNode props = getNotionPage(pageId).path("properties");
            assertThat(props.path("Type").path("select").path("name").asText()).isEqualTo("Task");
            assertThat(props.path("Complete").path("checkbox").asBoolean()).isFalse();
        } finally {
            deleteReminder(reminderId);
        }
    }

    @Test
    void a2_reminder_updated_in_apple_patches_notion() throws Exception {
        String originalTitle = "e2e-a2-orig-" + System.currentTimeMillis();
        String updatedTitle  = "e2e-a2-upd-"  + System.currentTimeMillis();
        String reminderId = createReminder(originalTitle, false);
        String pageId = awaitNotionPageByTitle(originalTitle, POLL_A);
        Instant t0 = Instant.now();
        try {
            updateReminder(reminderId, updatedTitle, false);
            awaitNotionTitle(pageId, updatedTitle, POLL_A);
            logLatency("A2 Apple UPDATED → Notion patched", t0);
        } finally {
            deleteReminder(reminderId);
        }
    }

    @Test
    void a3_reminder_completed_in_apple_marks_notion_done() throws Exception {
        String title = "e2e-a3-" + System.currentTimeMillis();
        String reminderId = createReminder(title, false);
        String pageId = awaitNotionPageByTitle(title, POLL_A);
        Instant t0 = Instant.now();
        try {
            updateReminder(reminderId, title, true);
            awaitNotionComplete(pageId, POLL_A);
            logLatency("A3 Apple COMPLETED → Notion Complete=true", t0);
        } finally {
            deleteReminder(reminderId);
        }
    }

    @Test
    void a4_reminder_deleted_in_apple_archives_notion_page() throws Exception {
        String title = "e2e-a4-" + System.currentTimeMillis();
        String reminderId = createReminder(title, false);
        String pageId = awaitNotionPageByTitle(title, POLL_A);
        Instant t0 = Instant.now();
        deleteReminder(reminderId);
        awaitNotionArchived(pageId, POLL_A);
        logLatency("A4 Apple DELETED → Notion archived", t0);
    }

    // ── Direction B: Notion → PG → Apple ──────────────────────────────────────

    @Test
    void b1_notion_task_created_triggers_apple_reminder() throws Exception {
        String title = "e2e-b1-" + System.currentTimeMillis();
        String pageId = createNotionTask(title);
        Instant t0 = Instant.now();
        try {
            awaitSentinelReminder(title, POLL_B);
            logLatency("B1 Notion CREATED → Apple reminder", t0);
        } finally {
            archiveNotionPage(pageId);
        }
    }

    @Test
    void b2_notion_task_updated_updates_apple_reminder() throws Exception {
        String originalTitle = "e2e-b2-orig-" + System.currentTimeMillis();
        String updatedTitle  = "e2e-b2-upd-"  + System.currentTimeMillis();
        String pageId = createNotionTask(originalTitle);
        awaitSentinelReminder(originalTitle, POLL_B);
        Instant t0 = Instant.now();
        try {
            updateNotionTitle(pageId, updatedTitle);
            awaitSentinelReminder(updatedTitle, POLL_B);
            logLatency("B2 Notion UPDATED → Apple reminder updated", t0);
        } finally {
            archiveNotionPage(pageId);
        }
    }

    @Test
    void b3_notion_task_completed_completes_apple_reminder() throws Exception {
        String title = "e2e-b3-" + System.currentTimeMillis();
        String pageId = createNotionTask(title);
        awaitSentinelReminder(title, POLL_B);
        Instant t0 = Instant.now();
        try {
            completeNotionPage(pageId, title);
            awaitSentinelReminderCompleted(title, POLL_B);
            logLatency("B3 Notion COMPLETED → Apple reminder completed", t0);
        } finally {
            archiveNotionPage(pageId);
        }
    }

    @Test
    void b4_notion_page_archived_deletes_apple_reminder() throws Exception {
        String title = "e2e-b4-" + System.currentTimeMillis();
        String pageId = createNotionTask(title);
        awaitSentinelReminder(title, POLL_B);
        Instant t0 = Instant.now();
        archiveNotionPage(pageId);
        awaitSentinelReminderGone(title, POLL_B);
        logLatency("B4 Notion ARCHIVED → Apple reminder deleted", t0);
    }

    // ── SentinelAPI helpers ───────────────────────────────────────────────────

    private String createReminder(String title, boolean completed) throws Exception {
        String body = """
            {"title":"%s","completed":%s,"priority":0,"list_id":"","list_name":"","alarms":[]}
            """.formatted(title, completed);
        HttpRequest req = HttpRequest.newBuilder(URI.create(sentinelBase + "/reminders"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("POST /reminders").isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("id").asText();
    }

    private void updateReminder(String id, String title, boolean completed) throws Exception {
        String body = """
            {"title":"%s","completed":%s,"priority":0,"list_id":"","list_name":"","alarms":[]}
            """.formatted(title, completed);
        HttpRequest req = HttpRequest.newBuilder(URI.create(sentinelBase + "/reminders/" + id))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private void deleteReminder(String id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(sentinelBase + "/reminders/" + id))
            .DELETE().build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode listSentinelReminders() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(sentinelBase + "/reminders"))
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? MAPPER.readTree(resp.body()) : MAPPER.createArrayNode();
    }

    private String awaitSentinelReminder(String title, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            for (JsonNode r : listSentinelReminders()) {
                if (title.equals(r.path("title").asText())) {
                    return r.path("id").asText();
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Reminder '%s' never appeared in SentinelAPI within %s".formatted(title, budget));
    }

    private void awaitSentinelReminderCompleted(String title, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            for (JsonNode r : listSentinelReminders()) {
                if (title.equals(r.path("title").asText()) && r.path("completed").asBoolean()) {
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Reminder '%s' never became completed in SentinelAPI within %s".formatted(title, budget));
    }

    private void awaitSentinelReminderGone(String title, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            boolean found = false;
            for (JsonNode r : listSentinelReminders()) {
                if (title.equals(r.path("title").asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Reminder '%s' was not removed from SentinelAPI within %s".formatted(title, budget));
    }

    // ── Notion API helpers ────────────────────────────────────────────────────

    private String createNotionTask(String title) throws Exception {
        String body = """
            {
              "parent": { "database_id": "%s" },
              "properties": {
                "Name": { "title": [{ "text": { "content": "%s" } }] },
                "Type": { "select": { "name": "Task" } },
                "Status": { "status": { "name": "Not started" } }
              }
            }
            """.formatted(notionTasksDb, title);
        HttpRequest req = notionRequest("/v1/pages")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("Notion POST /v1/pages").isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("id").asText().replace("-", "");
    }

    private void updateNotionTitle(String pageId, String title) throws Exception {
        patchNotion(pageId, """
            {"properties":{"Name":{"title":[{"text":{"content":"%s"}}]}}}
            """.formatted(title));
    }

    private void completeNotionPage(String pageId, String title) throws Exception {
        patchNotion(pageId, """
            {"properties":{"Complete":{"checkbox":true},"Status":{"status":{"name":"Done"}}}}
            """);
    }

    private void archiveNotionPage(String pageId) throws Exception {
        patchNotion(pageId, "{\"archived\":true}");
    }

    private void patchNotion(String pageId, String body) throws Exception {
        HttpRequest req = notionRequest("/v1/pages/" + pageId)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode getNotionPage(String pageId) throws Exception {
        HttpRequest req = notionRequest("/v1/pages/" + pageId).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body());
    }

    private String awaitNotionPageByTitle(String title, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            String pageId = findNotionPageIdByTitle(title);
            if (pageId != null) {
                return pageId;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Notion page '%s' never appeared within %s".formatted(title, budget));
    }

    private void awaitNotionTitle(String pageId, String expectedTitle, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            JsonNode titleArr = getNotionPage(pageId)
                .path("properties").path("Name").path("title");
            if (titleArr.isArray() && titleArr.size() > 0
                && expectedTitle.equals(titleArr.get(0).path("plain_text").asText())) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Notion page '%s' never updated to title '%s' within %s".formatted(pageId, expectedTitle, budget));
    }

    private void awaitNotionComplete(String pageId, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            if (getNotionPage(pageId).path("properties").path("Complete")
                    .path("checkbox").asBoolean()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Notion page '%s' never became Complete=true within %s".formatted(pageId, budget));
    }

    private void awaitNotionArchived(String pageId, Duration budget) throws Exception {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            JsonNode page = getNotionPage(pageId);
            if (page.path("archived").asBoolean() || page.path("in_trash").asBoolean()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
            "Notion page '%s' was not archived within %s".formatted(pageId, budget));
    }

    private String findNotionPageIdByTitle(String title) throws Exception {
        String body = """
            {"filter":{"property":"Name","title":{"equals":"%s"}}}
            """.formatted(title);
        HttpRequest req = notionRequest("/v1/databases/" + notionTasksDb + "/query")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        JsonNode results = MAPPER.readTree(resp.body()).path("results");
        return (results.isArray() && results.size() > 0)
            ? results.get(0).path("id").asText().replace("-", "")
            : null;
    }

    // ── HTTP builder ──────────────────────────────────────────────────────────

    private HttpRequest.Builder notionRequest(String path) {
        return HttpRequest.newBuilder(URI.create("https://api.notion.com" + path))
            .header("Authorization", "Bearer " + notionToken)
            .header("Notion-Version", NOTION_VERSION)
            .header("Content-Type", "application/json");
    }

    private static void logLatency(String label, Instant t0) {
        System.out.printf("[E2E] %s latency: %d ms%n",
            label, Duration.between(t0, Instant.now()).toMillis());
    }
}
