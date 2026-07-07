package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link NotionPort} adapter over the Notion REST API (version {@code 2025-09-03}).
 *
 * <p>Resilience (CA-8, RNF-01): outgoing calls are throttled to the configured minimum
 * interval (~3 rps) and retried up to {@code maxAttempts} with exponential backoff on
 * {@code 429} (honoring {@code Retry-After}), {@code 5xx} and I/O errors. {@code 404} maps to
 * {@link NotionPageNotFoundException} immediately; other {@code 4xx} are programming or
 * schema errors and fail fast. Exhausted retries raise {@link NotionApiException}.
 *
 * <p>Thread-safety: the throttle gate is synchronized; the outbox drain is effectively
 * single-threaded so contention is negligible.
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
class NotionRestClient implements NotionPort {

    private static final Logger log = LoggerFactory.getLogger(NotionRestClient.class);

    private final NotionSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Object throttleLock = new Object();
    private long nextRequestAtMs;

    NotionRestClient(NotionSyncProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // JdkClientHttpRequestFactory: the JDK HttpClient supports PATCH, which the default
        // HttpURLConnection-based factory does not.
        // HTTP/1.1 avoids the h2c upgrade dance: Notion terminates TLS/H2 at its edge anyway
        // and plain-HTTP stubs (WireMock) mis-read chunked bodies sent with an upgrade request.
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
            .defaultHeader("Notion-Version", properties.getNotionVersion())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .build();
    }

    @Override
    public String createPage(String dataSourceId, Map<String, Object> properties) {
        Map<String, Object> body = Map.of(
            "parent", Map.of("type", "data_source_id", "data_source_id", dataSourceId),
            "properties", properties);
        String response = exchange(HttpMethod.POST, "/v1/pages", body);
        return extractPageId(response);
    }

    @Override
    public void updatePage(String pageId, Map<String, Object> properties) {
        exchange(HttpMethod.PATCH, "/v1/pages/" + pageId, Map.of("properties", properties));
    }

    @Override
    public void archivePage(String pageId) {
        exchange(HttpMethod.PATCH, "/v1/pages/" + pageId, Map.of("archived", true));
    }

    @Override
    public String retrievePage(String pageId) {
        return exchange(HttpMethod.GET, "/v1/pages/" + pageId, null);
    }

    @Override
    public List<String> queryAllPages(String dataSourceId) {
        List<String> pages = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("page_size", 100);
            if (cursor != null) {
                body.put("start_cursor", cursor);
            }
            String response = exchange(HttpMethod.POST, "/v1/data_sources/" + dataSourceId + "/query", body);
            JsonNode root = parse(response);
            for (JsonNode page : root.path("results")) {
                pages.add(page.toString());
            }
            cursor = root.path("has_more").asBoolean(false)
                ? root.path("next_cursor").asText(null)
                : null;
        } while (cursor != null);
        return pages;
    }

    private String exchange(HttpMethod method, String path, Map<String, Object> body) {
        NotionApiException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            throttle();
            try {
                var request = restClient.method(method).uri(path);
                if (body != null) {
                    request.body(body);
                }
                return request.retrieve().body(String.class);
            } catch (RestClientResponseException ex) {
                HttpStatusCode status = ex.getStatusCode();
                if (status.value() == 404) {
                    throw new NotionPageNotFoundException(path);
                }
                if (status.value() == 400) {
                    String responseBody = ex.getResponseBodyAsString();
                    // Already-archived pages are in the target state; treat as gone (idempotent)
                    if (responseBody.contains("\"code\":\"validation_error\"")
                            && responseBody.contains("archived")) {
                        throw new NotionPageNotFoundException(path);
                    }
                }
                if (status.value() != 429 && !status.is5xxServerError()) {
                    throw new NotionApiException(
                        "Notion rejected %s %s with status %d".formatted(method, path, status.value()), ex);
                }
                lastFailure = new NotionApiException(
                    "Notion %s %s failed with status %d".formatted(method, path, status.value()), ex);
                backoff(attempt, retryAfterMs(ex));
            } catch (ResourceAccessException ex) {
                lastFailure = new NotionApiException(
                    "Notion %s %s unreachable".formatted(method, path), ex);
                backoff(attempt, null);
            }
        }
        throw lastFailure != null
            ? lastFailure
            : new NotionApiException("Notion %s %s failed".formatted(method, path));
    }

    /** Spaces requests to the configured minimum interval (~3 rps, RNF-01). */
    private void throttle() {
        long sleepMs;
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            sleepMs = nextRequestAtMs - now;
            nextRequestAtMs = Math.max(now, nextRequestAtMs) + properties.getMinRequestIntervalMs();
        }
        sleep(sleepMs);
    }

    private void backoff(int attempt, Long retryAfterMs) {
        if (attempt >= properties.getMaxAttempts()) {
            return;
        }
        long delay = retryAfterMs != null
            ? retryAfterMs
            : properties.getBackoffBaseMs() * (1L << (attempt - 1));
        log.warn("Notion call failed (attempt {}/{}); backing off {} ms",
            attempt, properties.getMaxAttempts(), delay);
        sleep(delay);
    }

    private Long retryAfterMs(RestClientResponseException ex) {
        String retryAfter = ex.getResponseHeaders() != null
            ? ex.getResponseHeaders().getFirst("Retry-After")
            : null;
        if (retryAfter == null) {
            return null;
        }
        try {
            return (long) (Double.parseDouble(retryAfter) * 1000);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String extractPageId(String responseJson) {
        String id = parse(responseJson).path("id").asText();
        if (id.isBlank()) {
            throw new NotionApiException("Notion create-page response carries no page id");
        }
        return id;
    }

    private JsonNode parse(String responseJson) {
        try {
            return objectMapper.readTree(responseJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new NotionApiException("Unparseable Notion API response", ex);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NotionApiException("Interrupted while waiting for the Notion rate limit", ex);
        }
    }
}
