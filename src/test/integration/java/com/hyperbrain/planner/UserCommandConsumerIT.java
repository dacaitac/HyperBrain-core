package com.hyperbrain.planner;

import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pipeline-level tests for the user-command consumer (HU-01b slice 2): the «calcular» button
 * (manual replan from now) and the manual Sleep Score input, consumed from
 * {@code user-commands.fifo}. Verified via DB state, mirroring {@code SqsConsumerIT} (no spy
 * beans — competing-listener gotcha). The wall clock is pinned to 13:00 of the fixture day so the
 * replan staleness guard is deterministic regardless of when the suite runs.
 */
@IntegrationTest
@TestPropertySource(properties = "app.user-commands.consumer.enabled=true")
@DisplayName("UserCommandConsumer — user-commands.fifo pipeline (HU-01b slice 2)")
class UserCommandConsumerIT {

    private static final String QUEUE = "user-commands.fifo";
    private static final String MESSAGE_GROUP = "user-commands";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // Within the cold-start fallback window (wake 06:30, bedtime 23:00, user pinned to UTC).
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime HALF_PAST_NOON =
        OffsetDateTime.of(2026, 7, 10, 12, 30, 0, 0, ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    /** Pinned "now" for the staleness guard: NOON commands are 1 h old — fresh. */
    private static final Instant PINNED_NOW = Instant.parse("2026-07-10T13:00:00Z");

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(PINNED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlannerStateRepository plannerStateRepository;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        // Pin the user to UTC so fixture instants and local-day projections agree.
        jdbcTemplate.update(
            "UPDATE sys_user SET timezone = 'UTC', settings = '{}'::jsonb WHERE id = ?", USER);
    }

    @Test
    @DisplayName("REPLAN_AGENDA plans the day from occurred_at; a second replan replaces, never duplicates")
    void replan_generates_blocks_from_now_without_duplicating() {
        // Given one schedulable task
        insertTask("Deep work", 0.9, 60);

        // When the «calcular» button fires at noon
        UUID first = UUID.randomUUID();
        send(replanBody(first, NOON), first.toString());

        // Then one PLANNED/PLANNER block materializes at or after the replan instant
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countPlannedBlocks()).isEqualTo(1));
        assertThat(earliestBlockStart()).isAfterOrEqualTo(NOON);
        // And the write-back rides the existing outbox path (AgendaBlockPlannedEvent staged)
        Integer staged = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'AgendaBlockPlannedEvent'",
            Integer.class);
        assertThat(staged).isEqualTo(1);

        // When the button fires again half an hour later (a new command)
        UUID second = UUID.randomUUID();
        send(replanBody(second, HALF_PAST_NOON), second.toString());

        // Then the day converges to a single regenerated set (delete-before-persist), from 12:30
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(earliestBlockStart()).isAfterOrEqualTo(HALF_PAST_NOON));
        assertThat(countPlannedBlocks()).isEqualTo(1);
    }

    @Test
    @DisplayName("SLEEP_SCORE upserts one row per day: a second score for the same day updates in place")
    void sleep_score_upserts_single_daily_row() {
        // When the user reports 85 for the day
        UUID first = UUID.randomUUID();
        send(sleepScoreBody(first, 85, DAY, NOON), first.toString());

        // Then a single score-only marker row exists: end_time NULL (invisible to the frontier),
        // start_time anchored at the day's local midnight only to satisfy NOT NULL
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));
        assertThat(sleepScore()).isEqualTo(85);
        Map<String, Object> row = sleepRecord();
        assertThat(row.get("end_time")).isNull();
        OffsetDateTime startTime = jdbcTemplate.queryForObject(
            "SELECT start_time FROM tel_sleep_record WHERE user_id = ?", OffsetDateTime.class, USER);
        assertThat(startTime.toInstant()).isEqualTo(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());

        // When the user corrects the score for the same day
        UUID second = UUID.randomUUID();
        send(sleepScoreBody(second, 90, DAY, HALF_PAST_NOON), second.toString());

        // Then the same row is updated — never a second row for the day
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepScore()).isEqualTo(90));
        assertThat(sleepRecordCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a manual score feeds the energy resolution but never the sleep-frontier median")
    void manual_score_feeds_energy_not_frontier() {
        // Given a manual score reported just now (fresh for the energy freshness bound)
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID commandId = UUID.randomUUID();
        send(sleepScoreBody(commandId, 42, now.toLocalDate(), now), commandId.toString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));

        // Then the energy input sees the score...
        assertThat(plannerStateRepository.loadLastNightSleepScore(USER, now)).isEqualTo(42);

        // ...but the frontier gets no samples from it (and no synthetic freshness voucher):
        // the fallback window stays in charge.
        SleepFrontierInputs inputs = plannerStateRepository.loadSleepFrontierInputs(USER, now);
        assertThat(inputs.wakeSamples()).isEmpty();
        assertThat(inputs.bedtimeSamples()).isEmpty();
    }

    @Test
    @DisplayName("energy prefers a device record (real hours) over a fresher manual marker, same day")
    void device_record_wins_energy_over_manual_marker() {
        // Given a manual marker reported just now (fresher collected_at than the device record)
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID commandId = UUID.randomUUID();
        send(sleepScoreBody(commandId, 42, now.toLocalDate(), now), commandId.toString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));

        // And a device record with real hours arriving afterwards (older collected_at)
        insertDeviceRecord(now.minusHours(8), now.minusHours(1), 70, now.minusMinutes(30));

        // Then the energy resolution sees the device score, not the fresher manual one
        assertThat(plannerStateRepository.loadLastNightSleepScore(USER, now)).isEqualTo(70);
    }

    @Test
    @DisplayName("a manual score never overwrites a complete device record (hours + score) of the day")
    void manual_score_does_not_override_device_record() {
        // Given a complete device record owning the day (wake on DAY, score present)
        insertDeviceRecord(
            OffsetDateTime.of(2026, 7, 9, 22, 30, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 7, 10, 6, 30, 0, 0, ZoneOffset.UTC),
            70,
            OffsetDateTime.of(2026, 7, 10, 11, 0, 0, 0, ZoneOffset.UTC));

        // When the user tries to overwrite it manually
        UUID manual = UUID.randomUUID();
        send(sleepScoreBody(manual, 90, DAY, NOON), manual.toString());
        // Fence on the same FIFO group: the control is only processed after the manual command
        UUID control = UUID.randomUUID();
        send(replanBody(control, NOON), control.toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("user-command:" + control)).isEqualTo(1));

        // Then the device record is untouched and no marker row was added
        assertThat(sleepRecordCount()).isEqualTo(1);
        assertThat(sleepScore()).isEqualTo(70);
        // The manual command was consumed (deduped) — just discarded downstream
        assertThat(countProcessed("user-command:" + manual)).isEqualTo(1);
    }

    @Test
    @DisplayName("a stale REPLAN_AGENDA (occurred_at older than the bound) is discarded without replanning")
    void stale_replan_is_discarded() {
        // Given a schedulable task that a live replan would turn into a block
        insertTask("Deep work", 0.9, 60);

        // When a replan 4 h older than the pinned now (bound = 2 h) arrives
        UUID stale = UUID.randomUUID();
        send(replanBody(stale, OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC)),
            stale.toString());
        // Fence on the same FIFO group with a sleep-score control (no planning side effects)
        UUID control = UUID.randomUUID();
        send(sleepScoreBody(control, 50, DAY, NOON), control.toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("user-command:" + control)).isEqualTo(1));

        // Then no blocks were planned; the stale command was consumed and marked, not retried
        assertThat(countPlannedBlocks()).isZero();
        assertThat(countProcessed("user-command:" + stale)).isEqualTo(1);
    }

    @Test
    @DisplayName("a redelivered command_id is processed once (consumer-side dedup)")
    void duplicate_command_id_is_processed_once() {
        // Given a first delivery recording 85
        UUID commandId = UUID.randomUUID();
        send(sleepScoreBody(commandId, 85, DAY, NOON), UUID.randomUUID().toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(sleepRecordCount()).isEqualTo(1));

        // When the same command_id is redelivered (distinct SQS dedup id) with a mutated body —
        // if dedup failed, the score would move to 99
        send(sleepScoreBody(commandId, 99, DAY, NOON), UUID.randomUUID().toString());
        // Fence: a later control command on the same FIFO group proves the duplicate was drained
        UUID control = UUID.randomUUID();
        send(replanBody(control, NOON), control.toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("user-command:" + control)).isEqualTo(1));

        // Then the duplicate left no trace: score untouched, one dedup row for the command
        assertThat(sleepScore()).isEqualTo(85);
        assertThat(countProcessed("user-command:" + commandId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an out-of-range score is discarded with ack: no row, no dedup mark, no DLQ retry loop")
    void invalid_score_is_discarded() {
        // Given an invalid score
        UUID invalid = UUID.randomUUID();
        send(sleepScoreBody(invalid, 150, DAY, NOON), invalid.toString());
        // Fence on the same FIFO group: the control can only be processed after the invalid one
        // was acked (same MessageGroupId ⇒ strictly sequential)
        UUID control = UUID.randomUUID();
        send(replanBody(control, NOON), control.toString());

        // When the control command lands
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("user-command:" + control)).isEqualTo(1));

        // Then the invalid command was dropped without side effects
        assertThat(sleepRecordCount()).isZero();
        assertThat(countProcessed("user-command:" + invalid)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void send(String body, String dedupId) {
        sqsTemplate.send(to -> to
            .queue(QUEUE)
            .payload(body)
            .messageGroupId(MESSAGE_GROUP)
            .messageDeduplicationId(dedupId));
    }

    private static String replanBody(UUID commandId, OffsetDateTime occurredAt) {
        return """
            {
              "command_id": "%s",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "%s",
              "payload": null
            }
            """.formatted(commandId, occurredAt);
    }

    private static String sleepScoreBody(UUID commandId, int score, LocalDate date,
                                         OffsetDateTime occurredAt) {
        return """
            {
              "command_id": "%s",
              "command_type": "SLEEP_SCORE",
              "origin": "USER",
              "occurred_at": "%s",
              "payload": { "score": %d, "date": "%s" }
            }
            """.formatted(commandId, occurredAt, score, date);
    }

    private void insertDeviceRecord(OffsetDateTime startTime, OffsetDateTime endTime, int score,
                                    OffsetDateTime collectedAt) {
        jdbcTemplate.update("""
            INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, collected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), USER, startTime, endTime, score, collectedAt);
    }

    private void insertTask(String name, double priority, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
    }

    private int countPlannedBlocks() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE status = 'PLANNED' AND origin = 'PLANNER'",
            Integer.class);
        return count == null ? 0 : count;
    }

    private OffsetDateTime earliestBlockStart() {
        return jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM core_time_block WHERE status = 'PLANNED' AND origin = 'PLANNER'",
            OffsetDateTime.class);
    }

    private int sleepRecordCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tel_sleep_record WHERE user_id = ?", Integer.class, USER);
        return count == null ? 0 : count;
    }

    private Map<String, Object> sleepRecord() {
        return jdbcTemplate.queryForMap(
            "SELECT sleep_score, start_time, end_time FROM tel_sleep_record WHERE user_id = ?", USER);
    }

    private Integer sleepScore() {
        return jdbcTemplate.queryForObject(
            "SELECT sleep_score FROM tel_sleep_record WHERE user_id = ?", Integer.class, USER);
    }

    private int countProcessed(String messageId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message WHERE message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }
}
