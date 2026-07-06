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
 * End-to-end smoke test for TD-03 (#21). Drives the <b>live</b> SentinelAPI REST API over Tailscale
 * and asserts that the resulting create/delete operations surface on the real {@code sync-events.fifo}
 * queue with the expected contract payload.
 *
 * <p>This is a manual smoke test, <b>not</b> part of CI: it needs a running SentinelAPI on the Mac
 * Mini and real AWS SQS. It is disabled unless {@code SENTINEL_SMOKE_ENABLED=true} and reads:
 *
 * <ul>
 *   <li>{@code SENTINEL_API_BASE_URL} — e.g. {@code http://mac-mini.tailnet.ts.net:8080}
 *   <li>{@code SYNC_EVENTS_QUEUE_URL} — full URL of {@code sync-events.fifo}
 *   <li>{@code AWS_REGION}, {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY} (default chain)
 * </ul>
 *
 * <p>The pure HU-09 integration test (payload injected directly into LocalStack, no SentinelAPI) is
 * separate ({@link SqsConsumerIT}). This one validates the producer side of the bridge.
 */
@Tag("smoke")
@EnabledIfEnvironmentVariable(named = "SENTINEL_SMOKE_ENABLED", matches = "true")
class SentinelApiSmokeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_BUDGET = Duration.ofSeconds(30);

    private final String baseUrl = System.getenv("SENTINEL_API_BASE_URL");
    private final String queueUrl = System.getenv("SYNC_EVENTS_QUEUE_URL");
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void reminderCrudSurfacesOnSyncEvents() throws Exception {
        String title = "smoke-reminder-" + System.currentTimeMillis();
        String body =
            """
            {"title":"%s","completed":false,"priority":0,"list_id":"","list_name":"","alarms":[]}
            """
                .formatted(title);

        String id = create("/reminders", body);
        try {
            JsonNode message = awaitMessage("REMINDER", "CREATED", id);
            assertThat(message.path("payload").path("title").asText()).isEqualTo(title);
        } finally {
            delete("/reminders/" + id);
        }
    }

    @Test
    void calendarEventCrudSurfacesOnSyncEvents() throws Exception {
        String title = "smoke-event-" + System.currentTimeMillis();
        String start = Instant.now().plusSeconds(3600).toString();
        String body =
            """
            {"title":"%s","start_time":"%s","all_day":false,"calendar_id":"","calendar_name":"","alarms":[]}
            """
                .formatted(title, start);

        String id = create("/events", body);
        try {
            JsonNode message = awaitMessage("CALENDAR_EVENT", "CREATED", id);
            assertThat(message.path("payload").path("title").asText()).isEqualTo(title);
        } finally {
            delete("/events/" + id);
        }
    }

    private String create(String path, String jsonBody) throws Exception {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("create %s", path).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("id").asText();
    }

    private void delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /** Polls sync-events.fifo until a matching message appears, deleting drained messages. */
    private JsonNode awaitMessage(String entityType, String operation, String entityId) throws Exception {
        try (SqsClient sqs = SqsClient.create()) {
            Instant deadline = Instant.now().plus(POLL_BUDGET);
            while (Instant.now().isBefore(deadline)) {
                List<Message> batch =
                    sqs.receiveMessage(
                            ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages(10)
                                .waitTimeSeconds(5)
                                .build())
                        .messages();
                for (Message message : batch) {
                    Optional<JsonNode> match = matches(message, entityType, operation, entityId);
                    // Delete every drained message so the smoke run leaves the queue clean.
                    sqs.deleteMessage(
                        DeleteMessageRequest.builder()
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
            "No %s/%s message for entity %s within %s".formatted(entityType, operation, entityId, POLL_BUDGET));
    }

    private Optional<JsonNode> matches(Message message, String entityType, String operation, String entityId) {
        try {
            JsonNode node = MAPPER.readTree(message.body());
            boolean hit =
                entityType.equals(node.path("entity_type").asText())
                    && operation.equals(node.path("operation").asText())
                    && entityId.equals(node.path("entity_id").asText());
            return hit ? Optional.of(node) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
