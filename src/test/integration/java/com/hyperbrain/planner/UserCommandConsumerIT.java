package com.hyperbrain.planner;

import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.PlannerBlockView;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    /** When the production backfill fixture was captured — the instant its anchor day is read from. */
    private static final OffsetDateTime BACKFILL_CAPTURED_AT =
        OffsetDateTime.of(2026, 8, 8, 11, 32, 0, 0, ZoneOffset.UTC);
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
        PlannerBlockView.create(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM context_event");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
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
        // And the write-back rides the standard executable path: the block announces itself
        Integer staged = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableCreatedEvent' "
                + "AND aggregate_id IN (SELECT id::text FROM core_executable WHERE type = 'TIME_BLOCK')",
            Integer.class);
        assertThat(staged).isEqualTo(1);

        // When the button fires again half an hour later (a new command)
        UUID second = UUID.randomUUID();
        send(replanBody(second, HALF_PAST_NOON), second.toString());

        // Then the day still holds exactly one block — the replan converges instead of duplicating.
        // It is the SAME block, still at 12:00: its start had already gone by, so the second replan
        // neither re-times it nor re-places the work inside it (ADR-040 D8, the past is never
        // rewritten). A morning already under way is not a draft to be reshuffled at 12:30.
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countPlannedBlocks()).isEqualTo(1));
        assertThat(earliestBlockStart()).isEqualTo(NOON);
    }

    @Test
    @DisplayName("REPLAN_AGENDA carrying a raw HealthKit dump distils a device night, archives it raw and replans")
    void replan_with_sleep_records_device_record_and_plans() {
        // Given one schedulable task
        insertTask("Deep work", 0.9, 60);

        // When the «calcular» button fires at noon with the raw stage dump inlined (U+202F and all)
        UUID commandId = UUID.randomUUID();
        send(replanWithSleepBody(commandId, NOON), commandId.toString());

        // Then a complete device record lands: real hours (end_time set), a score, the derived
        // duration (Core 6h + Deep 1h + REM 30m = 450 min), and a link to the archived raw dump
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT end_time, sleep_score, duration_minutes, context_event_id
            FROM tel_sleep_record WHERE user_id = ?
            """, USER);
        assertThat(row.get("end_time")).isNotNull();
        assertThat((Integer) row.get("sleep_score")).isGreaterThan(0);
        assertThat(row.get("duration_minutes")).isEqualTo(450);
        // The typed row points back at the dump it was derived from: totals cannot be un-collapsed, so
        // without the raw envelope a night already scored can never be recomputed (ADR-016 raw-first).
        assertThat(row.get("context_event_id")).isEqualTo(archivedDumpId());

        // And the agenda still materializes from the replan (same transaction)
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countPlannedBlocks()).isEqualTo(1));
        assertThat(earliestBlockStart()).isAfterOrEqualTo(NOON);
    }

    @Test
    @DisplayName("a napping day is scored on ALL of its sleep, keeps the night's hours, and keeps its naps")
    void replan_with_a_nap_sums_the_day_and_preserves_both_sessions() {
        // The production defect, end to end. The dump held the night AND an afternoon nap; the parser
        // kept the most recent session and called it the night, so the day was scored on the nap alone
        // (189 min → 13, against the 74-82 of the nights around it). Everything that had to change to
        // fix that meets in this one row, so it is asserted as one thing.
        insertTask("Deep work", 0.9, 60);

        UUID commandId = UUID.randomUUID();
        send(replanWithNightAndNapBody(commandId, NOON), commandId.toString());

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT duration_minutes, sleep_score FROM tel_sleep_record WHERE user_id = ?
            """, USER);
        // Scored on the whole day: 450 min of night + 116 of nap. The nap alone would be 116.
        assertThat(row.get("duration_minutes")).isEqualTo(566);
        assertThat((Integer) row.get("sleep_score")).isGreaterThan(50);
        // The row's two instant columns are the chronotype the sleep frontier takes its wake median
        // from, so they stay the NIGHT's: a nap that ended at 11:56 must never become the learned wake.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT start_time FROM tel_sleep_record WHERE user_id = ?", OffsetDateTime.class, USER))
            .isEqualTo(OffsetDateTime.of(2026, 7, 9, 23, 0, 0, 0, ZoneOffset.UTC));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT end_time FROM tel_sleep_record WHERE user_id = ?", OffsetDateTime.class, USER))
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 6, 40, 0, 0, ZoneOffset.UTC));

        // And both sessions survive into the row and come back out through the planner's own port —
        // which is what lets the day know WHEN he slept, not only how much.
        assertThat(plannerStateRepository.loadRecentSleepSessions(USER, NOON))
            .extracting(session -> session.start().toString() + ".." + session.end().toString())
            .containsExactly("2026-07-09T23:00Z..2026-07-10T06:40Z", "2026-07-10T10:00Z..2026-07-10T11:56Z");
    }

    @Test
    @DisplayName("the raw dump is archived verbatim, one row per night, rewritten by every re-send")
    void the_raw_dump_is_archived_and_rewritten_per_night() {
        // Six production rows could not be recomputed when the reading of sleep changed, because only
        // the collapsed totals had been kept. The raw envelope is what makes that recoverable — and
        // because the phone re-sends the same night on every replan and the watch re-stages it in
        // between, there must be exactly one row per night, holding the newest reading.
        UUID first = UUID.randomUUID();
        send(replanWithSleepBody(first, NOON), first.toString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(archivedDumpCount()).isEqualTo(1));

        UUID second = UUID.randomUUID();
        send(replanWithNightAndNapBody(second, HALF_PAST_NOON), second.toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(archivedDump().get("payload").toString()).contains("11:56"));

        // Still one row for that night, and it is the second dump — not two near-duplicates.
        assertThat(archivedDumpCount()).isEqualTo(1);
        Map<String, Object> raw = archivedDump();
        assertThat(raw).containsEntry("source", "INTEGRATION")
            .containsEntry("provider", "APPLE_HEALTH")
            // Not the canonical SLEEP_SESSION type: that payload is one aggregated session, this is a
            // list of raw stage intervals, and a re-normalizer must never confuse the two.
            .containsEntry("event_type", "SLEEP_STAGE_DUMP")
            .containsEntry("normalization_status", "NORMALIZED")
            .containsEntry("dedup_key",
                "APPLE_HEALTH:SLEEP_STAGE_DUMP:" + USER + ":2026-07-10");
        // Verbatim where it counts: the provider's own local time strings and stage labels, not
        // instants the parser derived from them. (JSONB stores a normalized object — key order and
        // spacing are the column's, the values are the dump's.)
        assertThat(raw.get("payload").toString())
            .contains("09/07/2026 at 11:00 PM", "\"Awake\"", "10/07/2026 at 11:56 AM");
    }

    @Test
    @DisplayName("the real fortnight backfill: one row per night, naps only for today, re-sends converge")
    void a_backfill_records_one_row_per_night() throws Exception {
        // The 660-sample dump Daniel actually sent (25 Jul → 8 Aug), driven end to end through the same
        // queue and the same command the daily replan uses. Production archived it whole under a single
        // night's key with status ERROR, because the fortnight was read as one sleep day.
        UUID sleepCycle = insertSleepCycle();
        UUID first = UUID.randomUUID();
        send(replanWithBackfillBody(first, BACKFILL_CAPTURED_AT), first.toString());

        // Thirteen nights read — the oldest of the range is left to the next overlapping window — and
        // every one of them scorable: one row each, keyed on the night, not one row for the whole send.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(13));
        assertThat(archivedDumpCount()).isEqualTo(13);
        assertThat(jdbcTemplate.queryForList(
            "SELECT (end_time AT TIME ZONE 'UTC')::date::text FROM tel_sleep_record "
                + "WHERE user_id = ? ORDER BY end_time", String.class, USER))
            .containsExactly("2026-07-26", "2026-07-27", "2026-07-28", "2026-07-30", "2026-07-31",
                "2026-08-01", "2026-08-02", "2026-08-03", "2026-08-04", "2026-08-05", "2026-08-06",
                "2026-08-07", "2026-08-08");

        // Each night's bytes are filed under that night's own key, so a night stays re-derivable one at
        // a time — and nothing in his real history is refused, now that a night's accounted time is
        // measured as a union instead of TST added to WASO.
        assertThat(jdbcTemplate.queryForList(
            "SELECT dedup_key FROM context_event WHERE user_id = ? AND event_type = 'SLEEP_STAGE_DUMP' "
                + "ORDER BY dedup_key", String.class, USER))
            .startsWith("APPLE_HEALTH:SLEEP_STAGE_DUMP:" + USER + ":2026-07-26")
            .endsWith("APPLE_HEALTH:SLEEP_STAGE_DUMP:" + USER + ":2026-08-08");
        assertThat(countArchivedWithStatus("NORMALIZED")).isEqualTo(13);
        assertThat(countArchivedWithStatus("ERROR")).isZero();

        // Not one «Siesta» in the past. The 5th and the 7th of August hold real naps (the 7th's is the
        // 09:20–13:56 stretch that broke production), and a history that recorded them would have
        // created executables with their calendar events and their Notion pages for afternoons weeks
        // gone. Only the day the run is standing on may do that, and that night had no nap.
        assertThat(napCount(sleepCycle)).isZero();

        // And a re-send of an overlapping range converges: the archive rewrites each night in place and
        // the score row is found by its own local day and updated. Daniel sends in overlapping batches
        // on purpose — the second pass must add nothing.
        UUID second = UUID.randomUUID();
        send(replanWithBackfillBody(second, BACKFILL_CAPTURED_AT), second.toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("user-command:" + second)).isEqualTo(1));
        assertThat(sleepRecordCount()).isEqualTo(13);
        assertThat(archivedDumpCount()).isEqualTo(13);
        assertThat(napCount(sleepCycle)).isZero();
    }

    @Test
    @DisplayName("two overlapping backfill windows converge: nothing duplicated, nothing left unrecorded")
    void overlapping_backfill_windows_converge_and_lose_no_night() {
        // How Daniel actually sends history: overlapping batches, on purpose. It has to hold together
        // on two counts at once. Convergence — the night both windows carry is written once, because
        // both writes are keyed on the night rather than on the send. And completeness — the oldest
        // night of a window is dropped (a range query may have cut it in half, and a truncated reading
        // must never overwrite a complete row nor the bytes behind it), so the ONLY thing that keeps
        // that night is the other window, which carries it in the middle where nothing could have cut
        // it. This is the test that proves the two halves of that argument meet.
        UUID later = UUID.randomUUID();
        send(replanWithNightsBody(later, NOON, "10/07/2026 at 11:00 AM", 8, 9, 10), later.toString());
        // The 8th is this window's oldest and is deliberately left out: two nights land, not three.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(2));

        // The earlier window overlaps it by two nights (the 8th and the 9th) and reaches further back.
        UUID earlier = UUID.randomUUID();
        send(replanWithNightsBody(earlier, NOON, "09/07/2026 at 11:00 AM", 6, 7, 8, 9),
            earlier.toString());
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(countProcessed("user-command:" + earlier)).isEqualTo(1));

        // Four nights, one row each. The 9th arrived in both windows and was written once; the 8th,
        // dropped as the oldest of the later window, is recovered from the middle of the earlier one;
        // the 6th is the earlier window's own oldest and stays for a window older still.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(4));
        assertThat(jdbcTemplate.queryForList(
            "SELECT (end_time AT TIME ZONE 'UTC')::date::text FROM tel_sleep_record "
                + "WHERE user_id = ? ORDER BY end_time", String.class, USER))
            .containsExactly("2026-07-07", "2026-07-08", "2026-07-09", "2026-07-10");
        // And the raw archive converges the same way — one row per night, never one per send.
        assertThat(archivedDumpCount()).isEqualTo(4);
        assertThat(countArchivedWithStatus("NORMALIZED")).isEqualTo(4);
    }

    @Test
    @DisplayName("a dump the plausibility guards refuse is archived anyway, flagged, and scores nothing")
    void a_refused_dump_is_archived_for_diagnosis() {
        UUID commandId = UUID.randomUUID();
        send(replanWithImplausibleSleepBody(commandId, NOON), commandId.toString());

        // The replan still runs — the sleep is enrichment, never the primary action.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(archivedDumpCount()).isEqualTo(1));
        assertThat(sleepRecordCount()).isZero();
        // ERROR and not SKIPPED, so the retention sweep spares it: this row is the only thing that can
        // explain, after the fact, why a night has no score.
        assertThat(archivedDump()).containsEntry("normalization_status", "ERROR");
    }

    @Test
    @DisplayName("a night slept in two phases is ONE night, and its second half is never a «Siesta»")
    void a_broken_night_is_one_night_end_to_end() {
        // Daniel, 2026-08-08: «anoche dormí en dos fases porque me desperté en la madrugada». Reading
        // the row's hours off the longer stretch declared he got up at 03:00 — which the sleep frontier
        // then learned as his wake time — and put a calendar event titled «Siesta» at half past six.
        UUID sleepCycle = insertSleepCycle();
        UUID commandId = UUID.randomUUID();

        send(replanWithBrokenNightBody(commandId, NOON), commandId.toString());

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));
        // The row spans the whole night on the clock, hole included: 23:00 to 08:00.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT start_time FROM tel_sleep_record WHERE user_id = ?", OffsetDateTime.class, USER))
            .isEqualTo(OffsetDateTime.of(2026, 7, 9, 23, 0, 0, 0, ZoneOffset.UTC));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT end_time FROM tel_sleep_record WHERE user_id = ?", OffsetDateTime.class, USER))
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 8, 0, 0, 0, ZoneOffset.UTC));
        // Nothing is filed as a nap, and both stretches still survive so the day knows WHEN he slept.
        assertThat(napCount(sleepCycle)).isZero();
        assertThat(plannerStateRepository.loadRecentSleepSessions(USER, NOON)).hasSize(2);
        // And the score is computed on the 5 h 30 actually slept, not on the 9 h the row spans.
        assertThat(jdbcTemplate.queryForMap(
            "SELECT duration_minutes FROM tel_sleep_record WHERE user_id = ?", USER))
            .containsEntry("duration_minutes", 330);
    }

    @Test
    @DisplayName("the day's nap becomes ONE done activity under «Sueño», however many replans re-send it")
    void a_nap_is_recorded_once_as_a_done_activity() {
        // The dump is re-sent on every replan of the day, so the second pass is the real test: a second
        // insert would leave two activities and two calendar events for one afternoon.
        UUID sleepCycle = insertSleepCycle();

        UUID first = UUID.randomUUID();
        send(replanWithNightAndNapBody(first, NOON), first.toString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(napCount(sleepCycle)).isEqualTo(1));

        UUID second = UUID.randomUUID();
        send(replanWithNightAndNapBody(second, HALF_PAST_NOON), second.toString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(sleepRecordCount()).isEqualTo(1));

        // Still one activity, over the nap's real hours — and the night is not among them.
        assertThat(napCount(sleepCycle)).isEqualTo(1);
        Map<String, Object> nap = jdbcTemplate.queryForMap(
            "SELECT name, status, start_time, end_time FROM core_executable "
                + "WHERE type = 'ACTIVITY' AND cycle_id = ?", sleepCycle);
        assertThat(nap).containsEntry("name", "Siesta").containsEntry("status", "DONE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE type = 'ACTIVITY' AND cycle_id = ?",
            OffsetDateTime.class, sleepCycle))
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 10, 0, 0, 0, ZoneOffset.UTC));

        // And it reached the satellites the same way every other executable does: through the outbox.
        Integer announced = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableCreatedEvent' "
                + "AND aggregate_id IN (SELECT id::text FROM core_executable WHERE type = 'ACTIVITY')",
            Integer.class);
        assertThat(announced).isEqualTo(1);
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

    /**
     * The same night as {@link #replanWithSleepBody} plus the nap that broke production. The nap starts
     * 3 h 20 after the night ends, so the clusterer sees two sessions rather than one long one.
     */
    private static String replanWithNightAndNapBody(UUID commandId, OffsetDateTime occurredAt) {
        return """
            {
              "command_id": "%s",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "%s",
              "sleep": {
                "date": "10/07/2026 at 12:05 PM",
                "sample": [
                  {"stage":"Core","startDate":"09/07/2026 at 11:00 PM","endDate":"10/07/2026 at 5:00 AM","duration":"6:00:00"},
                  {"stage":"Deep","startDate":"10/07/2026 at 5:00 AM","endDate":"10/07/2026 at 6:00 AM","duration":"1:00:00"},
                  {"stage":"REM","startDate":"10/07/2026 at 6:00 AM","endDate":"10/07/2026 at 6:30 AM","duration":"30:00"},
                  {"stage":"Awake","startDate":"10/07/2026 at 6:30 AM","endDate":"10/07/2026 at 6:40 AM","duration":"10:00"},
                  {"stage":"Core","startDate":"10/07/2026 at 10:00 AM","endDate":"10/07/2026 at 11:56 AM","duration":"1:56:00"}
                ]
              }
            }
            """.formatted(commandId, occurredAt);
    }

    /**
     * A dump the plausibility guards refuse: seventeen hours of sleep in one day, which is duplicated
     * days or a mis-parsed year rather than a long night. Nothing scorable comes out of it, and that is
     * the point.
     *
     * <p>Deliberately <b>not</b> an {@code Awake} stretch laid over the sleep it revises: that shape is
     * what Apple Watch actually produces and it must be read, not refused (see the parser's accounted
     * time, measured as a union).
     */
    private static String replanWithImplausibleSleepBody(UUID commandId, OffsetDateTime occurredAt) {
        return """
            {
              "command_id": "%s",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "%s",
              "sleep": {
                "date": "10/07/2026 at 12:05 PM",
                "sample": [
                  {"stage":"Core","startDate":"09/07/2026 at 6:00 PM","endDate":"10/07/2026 at 11:00 AM","duration":"17:00:00"}
                ]
              }
            }
            """.formatted(commandId, occurredAt);
    }

    /**
     * A night broken in the small hours: 23:00–03:00 and then 06:30–08:00, the shape Daniel actually
     * slept. Both stretches start before nine, so both are the night.
     */
    private static String replanWithBrokenNightBody(UUID commandId, OffsetDateTime occurredAt) {
        return """
            {
              "command_id": "%s",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "%s",
              "sleep": {
                "date": "10/07/2026 at 12:05 PM",
                "sample": [
                  {"stage":"Core","startDate":"09/07/2026 at 11:00 PM","endDate":"10/07/2026 at 3:00 AM","duration":"4:00:00"},
                  {"stage":"Core","startDate":"10/07/2026 at 6:30 AM","endDate":"10/07/2026 at 8:00 AM","duration":"1:30:00"}
                ]
              }
            }
            """.formatted(commandId, occurredAt);
    }

    private static String replanWithSleepBody(UUID commandId, OffsetDateTime occurredAt) {
        // Raw HealthKit dump (the Shortcut's shape) in the user's zone (UTC in this suite), local time
        // strings with the U+202F narrow no-break space Apple inserts before AM/PM. Core 6 h + Deep 1 h
        // + REM 30 m → TST 27 000 s = 450 min; window 23:00 → 06:40 on DAY.
        return """
            {
              "command_id": "%s",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "%s",
              "sleep": {
                "date": "10/07/2026 at 12:05 PM",
                "sample": [
                  {"stage":"Core","startDate":"09/07/2026 at 11:00 PM","endDate":"10/07/2026 at 5:00 AM","duration":"6:00:00"},
                  {"stage":"Deep","startDate":"10/07/2026 at 5:00 AM","endDate":"10/07/2026 at 6:00 AM","duration":"1:00:00"},
                  {"stage":"REM","startDate":"10/07/2026 at 6:00 AM","endDate":"10/07/2026 at 6:30 AM","duration":"30:00"},
                  {"stage":"Awake","startDate":"10/07/2026 at 6:30 AM","endDate":"10/07/2026 at 6:40 AM","duration":"10:00"}
                ]
              }
            }
            """.formatted(commandId, occurredAt);
    }

    /**
     * The command Daniel's Shortcut sends when the date range is widened: the same {@code REPLAN_AGENDA}
     * with the same {@code sleep} envelope, carrying the production dump verbatim from the fixture the
     * parser's unit test also reads — 660 samples, ~63 KB, well inside the 256 KB an SQS message holds.
     */
    private static String replanWithBackfillBody(UUID commandId, OffsetDateTime occurredAt)
        throws IOException {
        try (InputStream in = UserCommandConsumerIT.class
            .getResourceAsStream("/fixtures/shortcut_sleep_backfill.json")) {
            assertThat(in).as("backfill fixture on the integration classpath").isNotNull();
            String dump = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return """
                {
                  "command_id": "%s",
                  "command_type": "REPLAN_AGENDA",
                  "origin": "USER",
                  "occurred_at": "%s",
                  "sleep": %s
                }
                """.formatted(commandId, occurredAt, dump);
        }
    }

    /**
     * A backfill window covering the given sleep days of July 2026: one 23:00 → 05:00 night each, all
     * in one dump, captured at {@code capturedAt} (which is what anchors the run). The shape of the
     * command is the everyday one — only the range the Shortcut queried is wider.
     *
     * @param capturedAt the dump's own capture date, in the provider's local format
     * @param sleepDays  the days of July the nights are labelled by — the day he woke into, ascending
     */
    private static String replanWithNightsBody(UUID commandId, OffsetDateTime occurredAt,
                                               String capturedAt, int... sleepDays) {
        StringBuilder samples = new StringBuilder();
        for (int sleepDay : sleepDays) {
            if (!samples.isEmpty()) {
                samples.append(",\n              ");
            }
            samples.append(
                "{\"stage\":\"Core\",\"startDate\":\"%02d/07/2026 at 11:00 PM\",\"endDate\":\"%02d/07/2026 at 5:00 AM\",\"duration\":\"6:00:00\"}"
                    .formatted(sleepDay - 1, sleepDay));
        }
        return """
            {
              "command_id": "%s",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "%s",
              "sleep": {
                "date": "%s",
                "sample": [
                  %s
                ]
              }
            }
            """.formatted(commandId, occurredAt, capturedAt, samples);
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
            "SELECT count(*) FROM planner_blocks WHERE status = 'PLANNED' AND origin = 'PLANNER'",
            Integer.class);
        return count == null ? 0 : count;
    }

    private OffsetDateTime earliestBlockStart() {
        return jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM planner_blocks WHERE status = 'PLANNED' AND origin = 'PLANNER'",
            OffsetDateTime.class);
    }

    private int countArchivedWithStatus(String status) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM context_event WHERE user_id = ? AND event_type = 'SLEEP_STAGE_DUMP' "
                + "AND normalization_status = ?", Integer.class, USER, status);
        return count == null ? 0 : count;
    }

    private int archivedDumpCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM context_event WHERE user_id = ? AND event_type = 'SLEEP_STAGE_DUMP'",
            Integer.class, USER);
        return count == null ? 0 : count;
    }

    private Map<String, Object> archivedDump() {
        return jdbcTemplate.queryForMap(
            "SELECT id, source, provider, event_type, payload, dedup_key, normalization_status "
                + "FROM context_event WHERE user_id = ? AND event_type = 'SLEEP_STAGE_DUMP'", USER);
    }

    private UUID archivedDumpId() {
        return (UUID) archivedDump().get("id");
    }

    /** The user's «Sueño» cycle, which is what a recorded nap is filed under. */
    private UUID insertSleepCycle() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status)
            VALUES (?, ?, 'Sueño', 'PHASE', 'ACTIVE')
            """, id, USER);
        return id;
    }

    private int napCount(UUID sleepCycleId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE user_id = ? AND type = 'ACTIVITY' "
                + "AND cycle_id = ?", Integer.class, USER, sleepCycleId);
        return count == null ? 0 : count;
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
