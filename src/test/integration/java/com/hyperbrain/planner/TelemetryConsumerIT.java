package com.hyperbrain.planner;

import com.hyperbrain.planner.infrastructure.telemetry.TelemetryRetentionService;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pipeline-level tests for the raw-first telemetry consumer (ADR-016 #59): envelopes consumed from the
 * Standard {@code telemetry-events} queue land raw in {@code context_event} and are normalized into the
 * typed {@code tel_*} tables. Verified via DB state, mirroring {@code UserCommandConsumerIT} (no spy
 * beans — competing-listener gotcha); the consumer gate is flipped on only for this class so exactly
 * one context polls the queue.
 */
@IntegrationTest
@TestPropertySource(properties = "app.telemetry.consumer.enabled=true")
@DisplayName("TelemetryConsumer — telemetry-events raw-first pipeline (ADR-016 #59)")
class TelemetryConsumerIT {

    private static final String QUEUE = "telemetry-events";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TelemetryRetentionService retentionService;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM tel_app_usage");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM context_event");
        jdbcTemplate.update("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC', settings = '{}'::jsonb WHERE id = ?", USER);
    }

    @Test
    @DisplayName("APPLE_HEALTH/SLEEP_SESSION lands raw and normalizes into a device tel_sleep_record")
    void sleep_session_normalizes_to_device_record() {
        send(sleepBody(UUID.randomUUID()));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT start_time, end_time, duration_minutes, sleep_score, context_event_id FROM tel_sleep_record WHERE user_id = ?",
            USER);
        // A device record: real hours + score, traced to the raw envelope.
        assertThat(row.get("end_time")).isNotNull();
        assertThat(row.get("sleep_score")).isEqualTo(100);
        assertThat(row.get("duration_minutes")).isEqualTo(480);
        assertThat(row.get("context_event_id")).isNotNull();
        // The raw row is kept and marked NORMALIZED.
        assertThat(countContextEvents("NORMALIZED")).isEqualTo(1);
    }

    @Test
    @DisplayName("DEVICE_ACTIVITY/APP_ACTIVITY lands raw and normalizes into one tel_app_usage row per bucket")
    void app_activity_normalizes_to_usage_rows() {
        send(appBody(UUID.randomUUID()));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(appUsageCount()).isEqualTo(2));
        assertThat(countContextEvents("NORMALIZED")).isEqualTo(1);
        Integer social = jdbcTemplate.queryForObject(
            "SELECT duration_seconds FROM tel_app_usage WHERE user_id = ? AND category = 'SOCIAL'",
            Integer.class, USER);
        assertThat(social).isEqualTo(1200);
    }

    @Test
    @DisplayName("a device record wins over an existing manual marker of the same day (converted in place)")
    void device_record_supersedes_manual_marker() {
        // Given a manual score-only marker for the wake day (end_time NULL, midnight anchor).
        jdbcTemplate.update("""
            INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, collected_at)
            VALUES (?, ?, '2026-07-11T00:00:00Z', NULL, 55, '2026-07-11T06:00:00Z')
            """, UUID.randomUUID(), USER);

        // When a device SLEEP_SESSION for the same day arrives.
        send(sleepBody(UUID.randomUUID()));

        // Then the single row is now the device record (real hours, device score), never a duplicate.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(deviceRecordCount()).isEqualTo(1));
        assertThat(sleepRecordCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT end_time, sleep_score, context_event_id FROM tel_sleep_record WHERE user_id = ?", USER);
        assertThat(row.get("end_time")).isNotNull();
        assertThat(row.get("sleep_score")).isEqualTo(100);
        assertThat(row.get("context_event_id")).isNotNull();
    }

    @Test
    @DisplayName("an unknown (provider, event_type) lands raw as SKIPPED — no typed row, never DLQ")
    void unknown_provider_is_skipped_not_dlq() {
        send(unknownBody(UUID.randomUUID()));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countContextEvents("SKIPPED")).isEqualTo(1));
        assertThat(sleepRecordCount()).isZero();
        assertThat(appUsageCount()).isZero();
    }

    @Test
    @DisplayName("a redelivered event_id is idempotent: one raw row, one device record")
    void duplicate_event_id_is_idempotent() {
        UUID eventId = UUID.randomUUID();
        send(sleepBody(eventId));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));

        // Redeliver the same event_id; idempotency (event_id + dedup_key + per-day upsert) keeps it at one.
        send(sleepBody(eventId));
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("telemetry:" + eventId)).isEqualTo(1));
        assertThat(sleepRecordCount()).isEqualTo(1);
        assertThat(countContextEvents("NORMALIZED")).isEqualTo(1);
    }

    @Test
    @DisplayName("retention purges old NORMALIZED/SKIPPED raw rows but keeps ERROR rows")
    void retention_purges_processed_keeps_error() {
        insertRawAt("NORMALIZED", 100);
        insertRawAt("SKIPPED", 100);
        insertRawAt("ERROR", 100);
        insertRawAt("NORMALIZED", 1);   // recent — inside the 90-day window

        int purged = retentionService.purgeExpired();

        assertThat(purged).isEqualTo(2);
        assertThat(countContextEvents("ERROR")).isEqualTo(1);
        assertThat(countContextEvents("NORMALIZED")).isEqualTo(1);
        assertThat(countContextEvents("SKIPPED")).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void send(String body) {
        sqsTemplate.send(to -> to.queue(QUEUE).payload(body));
    }

    private static String sleepBody(UUID eventId) {
        return """
            {
              "event_id": "%s",
              "source_system": "LAMBDA_TELEMETRY",
              "provider": "APPLE_HEALTH",
              "event_type": "SLEEP_SESSION",
              "schema_version": "1",
              "occurred_at": "2026-07-11T06:30:00Z",
              "collected_at": "2026-07-11T07:00:00Z",
              "payload": {
                "start_time": "2026-07-10T22:00:00Z",
                "end_time": "2026-07-11T06:30:00Z",
                "core_seconds": 17280, "deep_seconds": 5184, "rem_seconds": 6336, "awake_seconds": 600
              }
            }
            """.formatted(eventId);
    }

    private static String appBody(UUID eventId) {
        return """
            {
              "event_id": "%s",
              "source_system": "LAMBDA_TELEMETRY",
              "provider": "DEVICE_ACTIVITY",
              "event_type": "APP_ACTIVITY",
              "schema_version": "1",
              "occurred_at": "2026-07-11T10:00:00Z",
              "collected_at": "2026-07-11T10:05:00Z",
              "payload": {
                "buckets": [
                  { "bucket_start": "2026-07-11T08:00:00Z", "bucket_end": "2026-07-11T09:00:00Z",
                    "category": "SOCIAL", "duration_seconds": 1200, "pickups": 15 },
                  { "bucket_start": "2026-07-11T09:00:00Z", "bucket_end": "2026-07-11T10:00:00Z",
                    "category": "PRODUCTIVITY", "duration_seconds": 3000 }
                ]
              }
            }
            """.formatted(eventId);
    }

    private static String unknownBody(UUID eventId) {
        return """
            {
              "event_id": "%s",
              "source_system": "POLLER_RESCUETIME",
              "provider": "RESCUETIME",
              "event_type": "WINDOW_ACTIVITY",
              "schema_version": "1",
              "occurred_at": "2026-07-11T10:00:00Z",
              "collected_at": "2026-07-11T10:05:00Z",
              "payload": { "app": "Xcode", "seconds": 900 }
            }
            """.formatted(eventId);
    }

    private void insertRawAt(String status, int daysAgo) {
        jdbcTemplate.update("""
            INSERT INTO context_event
                (id, user_id, source, provider, event_type, payload, occurred_at,
                 dedup_key, schema_version, ingested_at, normalization_status)
            VALUES (?, ?, 'INTEGRATION', 'APPLE_HEALTH', 'SLEEP_SESSION', '{}'::jsonb, now(),
                    ?, '1', now() - make_interval(days => ?), ?)
            """, UUID.randomUUID(), USER, UUID.randomUUID().toString(), daysAgo, status);
    }

    private int sleepRecordCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tel_sleep_record WHERE user_id = ?", Integer.class, USER);
        return count == null ? 0 : count;
    }

    private int deviceRecordCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tel_sleep_record WHERE user_id = ? AND end_time IS NOT NULL", Integer.class, USER);
        return count == null ? 0 : count;
    }

    private int appUsageCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tel_app_usage WHERE user_id = ?", Integer.class, USER);
        return count == null ? 0 : count;
    }

    private int countContextEvents(String status) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM context_event WHERE user_id = ? AND normalization_status = ?",
            Integer.class, USER, status);
        return count == null ? 0 : count;
    }

    private int countProcessed(String messageId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message WHERE message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }
}
