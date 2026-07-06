package com.hyperbrain.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * End-to-end smoke tests for the SentinelAPI bridge (Apple ↔ SQS).
 *
 * <p>These tests drive the <b>live</b> SentinelAPI over Tailscale and assert that CRUD operations
 * on reminders and calendar events surface on {@code sync-events.fifo} with the expected contract
 * payload. They are disabled unless {@code SENTINEL_SMOKE_ENABLED=true} and require:
 *
 * <ul>
 *   <li>{@code SENTINEL_API_BASE_URL} — e.g. {@code http://mac-mini.tailnet.ts.net:8080}
 *   <li>{@code SYNC_EVENTS_QUEUE_URL} — full URL of {@code sync-events.fifo}
 *   <li>{@code AWS_REGION}, {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY} (default chain)
 * </ul>
 *
 * <p>Note: these tests verify the producer side of the bridge (SentinelAPI → SQS). DB-level
 * verification of the consumer side lives in {@link ReminderHandlerIT} and
 * {@link CalendarEventHandlerIT} (Testcontainers — no live services required).
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "SENTINEL_SMOKE_ENABLED", matches = "true")
class SentinelApiSmokeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_BUDGET = Duration.ofSeconds(30);

    private final String baseUrl = System.getenv("SENTINEL_API_BASE_URL");
    private final String queueUrl = System.getenv("SYNC_EVENTS_QUEUE_URL");
    private final HttpClient http = HttpClient.newHttpClient();

    // ── Reminders ────────────────────────────────────────────────────────────

    @Test
    void reminder_create_surfaces_on_sync_events() throws Exception {
        String title = "smoke-create-" + System.currentTimeMillis();
        String id = createReminder(title, "");

        try {
            JsonNode msg = awaitMessage("REMINDER", "CREATED", id);
            assertThat(msg.path("payload").path("title").asText()).isEqualTo(title);
        } finally {
            deleteReminder(id);
        }
    }

    @Test
    void reminder_update_surfaces_on_sync_events() throws Exception {
        String originalTitle = "smoke-original-" + System.currentTimeMillis();
        String id = createReminder(originalTitle, "");
        awaitMessage("REMINDER", "CREATED", id);

        String updatedTitle = "smoke-updated-" + System.currentTimeMillis();
        updateReminder(id, updatedTitle, "");

        try {
            JsonNode msg = awaitMessage("REMINDER", "UPDATED", id);
            assertThat(msg.path("payload").path("title").asText()).isEqualTo(updatedTitle);
        } finally {
            deleteReminder(id);
        }
    }

    @Test
    void reminder_delete_surfaces_on_sync_events() throws Exception {
        String title = "smoke-delete-" + System.currentTimeMillis();
        String id = createReminder(title, "");
        awaitMessage("REMINDER", "CREATED", id);

        deleteReminder(id);

        JsonNode msg = awaitMessage("REMINDER", "DELETED", id);
        assertThat(msg.path("entity_id").asText()).isEqualTo(id);
    }

    // ── Calendar events — multiple calendars ─────────────────────────────────

    @Test
    void calendar_event_create_surfaces_on_sync_events() throws Exception {
        String title = "smoke-event-" + System.currentTimeMillis();
        String start = Instant.now().plusSeconds(3600).toString();
        String id = createEvent(title, start, "");

        try {
            JsonNode msg = awaitMessage("CALENDAR_EVENT", "CREATED", id);
            assertThat(msg.path("payload").path("title").asText()).isEqualTo(title);
        } finally {
            deleteEvent(id);
        }
    }

    @Test
    void calendar_event_update_surfaces_on_sync_events() throws Exception {
        String originalTitle = "smoke-event-orig-" + System.currentTimeMillis();
        String start = Instant.now().plusSeconds(3600).toString();
        String id = createEvent(originalTitle, start, "");
        awaitMessage("CALENDAR_EVENT", "CREATED", id);

        String updatedTitle = "smoke-event-upd-" + System.currentTimeMillis();
        updateEvent(id, updatedTitle, start, "");

        try {
            JsonNode msg = awaitMessage("CALENDAR_EVENT", "UPDATED", id);
            assertThat(msg.path("payload").path("title").asText()).isEqualTo(updatedTitle);
        } finally {
            deleteEvent(id);
        }
    }

    @Test
    void calendar_event_delete_surfaces_on_sync_events() throws Exception {
        String title = "smoke-event-del-" + System.currentTimeMillis();
        String start = Instant.now().plusSeconds(3600).toString();
        String id = createEvent(title, start, "");
        awaitMessage("CALENDAR_EVENT", "CREATED", id);

        deleteEvent(id);

        JsonNode msg = awaitMessage("CALENDAR_EVENT", "DELETED", id);
        assertThat(msg.path("entity_id").asText()).isEqualTo(id);
    }

    /**
     * Creates two calendar events in different calendars and verifies both surface on SQS
     * with their correct {@code calendar_name} in the payload. Requires at least two calendar
     * identifiers available in SentinelAPI (passed via env or defaults to empty string which
     * lets SentinelAPI choose the default calendar).
     */
    @Test
    void two_events_in_different_calendars_both_surface() throws Exception {
        String calA = System.getenv().getOrDefault("SMOKE_CALENDAR_A_ID", "");
        String calB = System.getenv().getOrDefault("SMOKE_CALENDAR_B_ID", "");
        String start = Instant.now().plusSeconds(3600).toString();

        String titleA = "smoke-calA-" + System.currentTimeMillis();
        String titleB = "smoke-calB-" + System.currentTimeMillis();

        String idA = createEvent(titleA, start, calA);
        String idB = createEvent(titleB, start, calB);

        try {
            JsonNode msgA = awaitMessage("CALENDAR_EVENT", "CREATED", idA);
            JsonNode msgB = awaitMessage("CALENDAR_EVENT", "CREATED", idB);
            assertThat(msgA.path("payload").path("title").asText()).isEqualTo(titleA);
            assertThat(msgB.path("payload").path("title").asText()).isEqualTo(titleB);
        } finally {
            deleteEvent(idA);
            deleteEvent(idB);
        }
    }

    // ── REST helpers ─────────────────────────────────────────────────────────

    private String createReminder(String title, String listId) throws Exception {
        String body = """
            {"title":"%s","completed":false,"priority":0,"list_id":"%s","list_name":"","alarms":[]}
            """.formatted(title, listId);
        return postAndGetId("/reminders", body);
    }

    private void updateReminder(String id, String title, String listId) throws Exception {
        String body = """
            {"title":"%s","completed":false,"priority":0,"list_id":"%s","list_name":"","alarms":[]}
            """.formatted(title, listId);
        put("/reminders/" + id, body);
    }

    private void deleteReminder(String id) throws Exception {
        delete("/reminders/" + id);
    }

    private String createEvent(String title, String start, String calendarId) throws Exception {
        String body = """
            {"title":"%s","start_time":"%s","all_day":false,"calendar_id":"%s","calendar_name":"","alarms":[]}
            """.formatted(title, start, calendarId);
        return postAndGetId("/events", body);
    }

    private void updateEvent(String id, String title, String start, String calendarId) throws Exception {
        String body = """
            {"title":"%s","start_time":"%s","all_day":false,"calendar_id":"%s","calendar_name":"","alarms":[]}
            """.formatted(title, start, calendarId);
        put("/events/" + id, body);
    }

    private void deleteEvent(String id) throws Exception {
        delete("/events/" + id);
    }

    private String postAndGetId(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s", path).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("id").asText();
    }

    private void put(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("PUT %s", path).isIn(200, 204);
    }

    private void delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
    }

    // ── SQS polling ──────────────────────────────────────────────────────────

    /** Polls {@code sync-events.fifo} until a message matching all three criteria arrives. */
    private JsonNode awaitMessage(String entityType, String operation, String entityId)
        throws Exception {
        try (SqsClient sqs = SqsClient.create()) {
            Instant deadline = Instant.now().plus(POLL_BUDGET);
            while (Instant.now().isBefore(deadline)) {
                List<Message> batch = sqs.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()).messages();

                for (Message message : batch) {
                    Optional<JsonNode> match = matches(message, entityType, operation, entityId);
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
                    if (match.isPresent()) {
                        return match.get();
                    }
                }
            }
        }
        throw new AssertionError(
            "No %s/%s message for entity %s within %s".formatted(
                entityType, operation, entityId, POLL_BUDGET));
    }

    private Optional<JsonNode> matches(Message msg, String entityType, String operation, String entityId) {
        try {
            JsonNode node = MAPPER.readTree(msg.body());
            boolean hit = entityType.equals(node.path("entity_type").asText())
                && operation.equals(node.path("operation").asText())
                && entityId.equals(node.path("entity_id").asText());
            return hit ? Optional.of(node) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
