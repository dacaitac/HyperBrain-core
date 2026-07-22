package com.hyperbrain.sync;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Inbound DELETE of a calendar event mapped to a morning-agenda block (#13): a user removing the
 * EKEvent of a {@code PLANNER} block in iOS must drop the {@code core_time_block} — always, regardless
 * of the mapping's age (empirical validation showed the ids do not mutate) — while executable-backed
 * events and unmapped events are handled correctly.
 */
@IntegrationTest
@TestPropertySource(properties = "app.sync.consumer.enabled=true")
@DisplayName("CALENDAR_EVENT DELETE → planner time-block removal (#13)")
class CalendarEventBlockDeletionIT {

    private static final String SYNC_QUEUE = "sync-events.fifo";
    private static final UUID USER_ID = DataFixture.SYSTEM_USER_ID;

    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM sync_mappings");
        jdbcTemplate.execute("DELETE FROM core_time_block");
        jdbcTemplate.execute("DELETE FROM core_executable");
        jdbcTemplate.execute("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("DELETE of a planner block's event (stale mapping): removes the block and its mapping, keeps the executable")
    void delete_removes_planner_block() {
        UUID executableId = insertExecutable("Write the report");
        UUID blockId = insertPlannerBlock(executableId);
        String entityId = "EKEvent-block-" + UUID.randomUUID();
        insertMapping(blockId, entityId, OffsetDateTime.now().minusHours(1));

        send(deletedCalendarEvent(entityId), entityId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countTimeBlock(blockId)).isZero();
            assertThat(countMapping(entityId)).isZero();
        });
        assertThat(countExecutable(executableId)).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE of a freshly mapped block: still propagates — removes the block and its mapping")
    void delete_removes_freshly_mapped_block() {
        UUID executableId = insertExecutable("Deep work");
        UUID blockId = insertPlannerBlock(executableId);
        String entityId = "EKEvent-fresh-" + UUID.randomUUID();
        // A mapping written "just now": the removed id-mutation guard used to skip this; it must not.
        insertMapping(blockId, entityId, OffsetDateTime.now());

        send(deletedCalendarEvent(entityId), entityId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countTimeBlock(blockId)).isZero();
            assertThat(countMapping(entityId)).isZero();
        });
        assertThat(countExecutable(executableId)).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE of an executable-backed event leaves an unrelated planner block untouched")
    void delete_executable_does_not_touch_blocks() {
        UUID executableId = insertExecutable("Doctor appointment");
        String entityId = "EKEvent-exec-" + UUID.randomUUID();
        insertMapping(executableId, entityId, OffsetDateTime.now().minusHours(1));

        UUID otherExecutable = insertExecutable("Unrelated task");
        UUID untouchedBlock = insertPlannerBlock(otherExecutable);

        send(deletedCalendarEvent(entityId), entityId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countExecutable(executableId)).isZero();
            assertThat(countMapping(entityId)).isZero();
        });
        assertThat(countTimeBlock(untouchedBlock)).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE of an unmapped event is an idempotent no-op")
    void delete_unmapped_event_is_noop() {
        UUID executableId = insertExecutable("Standing task");
        UUID blockId = insertPlannerBlock(executableId);
        String entityId = "EKEvent-unmapped-" + UUID.randomUUID();

        send(deletedCalendarEvent(entityId), entityId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(countProcessed()).isGreaterThanOrEqualTo(1));
        assertThat(countTimeBlock(blockId)).isEqualTo(1);
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    private UUID insertExecutable(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO core_executable (id, user_id, name, type, status) VALUES (?, ?, ?, 'TASK', 'TODO')",
            id, USER_ID, name);
        return id;
    }

    private UUID insertPlannerBlock(UUID executableId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin, planned_minutes)
            VALUES (?, ?, ?, ?, 'PLANNED', 'PLANNER', 60)
            """,
            id, executableId,
            OffsetDateTime.parse("2026-07-21T09:00:00-05:00"),
            OffsetDateTime.parse("2026-07-21T10:00:00-05:00"));
        return id;
    }

    private void insertMapping(UUID localId, String externalId, OffsetDateTime lastSyncedAt) {
        jdbcTemplate.update("""
            INSERT INTO sync_mappings
                (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
            VALUES (?, ?, ?, 'APPLE', ?, 'checksum', 'SYNCED', ?)
            """,
            UUID.randomUUID(), USER_ID, localId, externalId, lastSyncedAt);
    }

    // ── assertions ─────────────────────────────────────────────────────────────

    private int countTimeBlock(UUID blockId) {
        return count("SELECT count(*) FROM core_time_block WHERE id = ?", blockId);
    }

    private int countExecutable(UUID id) {
        return count("SELECT count(*) FROM core_executable WHERE id = ?", id);
    }

    private int countMapping(String externalId) {
        return count("SELECT count(*) FROM sync_mappings WHERE external_system='APPLE' AND external_id = ?",
            externalId);
    }

    private int countProcessed() {
        return count("SELECT count(*) FROM processed_message");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ── messaging ──────────────────────────────────────────────────────────────

    private void send(String body, String groupId) {
        sqsTemplate.send(to -> to
            .queue(SYNC_QUEUE)
            .payload(body)
            .messageGroupId(groupId)
            .messageDeduplicationId(UUID.randomUUID().toString()));
    }

    private static String deletedCalendarEvent(String entityId) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "CALENDAR_EVENT",
              "entity_id": "%s",
              "operation": "DELETED",
              "occurred_at": "2026-07-21T11:00:00-05:00",
              "payload": null
            }
            """.formatted(UUID.randomUUID(), entityId);
    }
}
