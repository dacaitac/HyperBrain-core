package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Planner's read side sees the <b>deployed</b> block model against a real
 * PostgreSQL: since ADR-039 a time block is a {@code core_executable} of type {@code TIME_BLOCK}, and
 * the frozen {@code core_time_block} table is history. Two reads decide the shape of the day and both
 * are exercised here — the occupancy walls (the Planner must never schedule over a real block) and the
 * goal-selector signals that feed the hysteresis and the anti-starvation valve.
 *
 * <p>Black-box: only the published {@link PlannerStateRepository} port is exercised; the selection
 * policy that consumes the signals is unit-tested in {@code WigPortfolioSelectorTest}.
 */
@IntegrationTest
@DisplayName("JdbcPlannerStateRepository — walls and goal signals read the deployed TIME_BLOCK model")
class JdbcPlannerStateRepositoryIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 8, 7);
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 8, 7, 12, 0, 0, 0, UTC);
    private static final OffsetDateTime DAY_START = OffsetDateTime.of(2026, 8, 7, 0, 0, 0, 0, UTC);
    private static final OffsetDateTime DAY_END = OffsetDateTime.of(2026, 8, 8, 0, 0, 0, 0, UTC);

    /** The sanctioned {@code degradedStreakThreshold}: the streak signal saturates here. */
    private static final int STREAK_BOUND = 3;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlannerStateRepository repository;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        // Pin the user to UTC so the fixtures' instants and the local-date projections agree.
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    // ─── occupancy walls ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a PLANNED TIME_BLOCK executable of the day is a wall, identified by its own id")
    void planned_time_block_executable_is_a_wall() {
        UUID block = insertTimeBlock("Deep work", "PLANNED", "PLANNER",
            at(9, 0), at(11, 0));

        List<OccupiedInterval> walls = repository.loadOccupiedIntervals(USER, DAY_START, DAY_END);

        assertThat(walls).singleElement().satisfies(wall -> {
            assertThat(wall.executableId()).isEqualTo(block);
            assertThat(wall.start()).isEqualTo(at(9, 0));
            assertThat(wall.end()).isEqualTo(at(11, 0));
            assertThat(wall.readOnlyAgenda()).isFalse();
        });
    }

    @Test
    @DisplayName("every lifecycle state of a real block walls: reserved, open, executing and closed")
    void open_and_closed_blocks_all_wall() {
        // The state a block Daniel creates by hand is born in: it arrives from Notion through the
        // ordinary ingestion of an executable, so nothing ever sets it to PLANNED.
        UUID reserved = insertUserBlock("Hyperbrain", at(19, 0), at(22, 0));
        UUID planned = insertTimeBlock("Planned", "PLANNED", "PLANNER", at(8, 0), at(9, 0));
        UUID running = insertTimeBlock("Running", "IN_PROGRESS", "USER", at(9, 30), at(10, 30));
        UUID done = insertTimeBlock("Done", "DONE", "PLANNER", at(11, 0), at(12, 0));
        // The expiry sweep closes an unfulfilled block: its window is in the past, and the past is
        // never rewritten, so it keeps walling (the legacy read dropped this state).
        UUID failed = insertTimeBlock("Failed", "FAILED", "PLANNER", at(13, 0), at(14, 0));
        UUID waiting = insertTimeBlock("Waiting", "WAITING", "USER", at(15, 0), at(16, 0));

        List<OccupiedInterval> walls = repository.loadOccupiedIntervals(USER, DAY_START, DAY_END);

        assertThat(walls).extracting(OccupiedInterval::executableId)
            .containsExactlyInAnyOrder(reserved, planned, running, done, failed, waiting);
    }

    @Test
    @DisplayName("a block the user reserved by hand walls in its initial state: TODO is not free time")
    void a_user_block_in_its_initial_state_is_a_wall() {
        // Exactly the production row that broke: created from Notion, so no origin and status TODO.
        UUID evening = insertUserBlock("Hyperbrain", at(19, 0), at(22, 0));

        List<OccupiedInterval> walls = repository.loadOccupiedIntervals(USER, DAY_START, DAY_END);

        // Had this block stayed invisible, the planner would have laid its 20:00 and 21:00 windows
        // straight on top of the three hours their owner had already spoken for.
        assertThat(walls).singleElement().satisfies(wall -> {
            assertThat(wall.executableId()).isEqualTo(evening);
            assertThat(wall.start()).isEqualTo(at(19, 0));
            assertThat(wall.end()).isEqualTo(at(22, 0));
            assertThat(wall.readOnlyAgenda()).isFalse();
        });
    }

    @Test
    @DisplayName("a FOCUS block never walls: it accounts for a task already running, it does not hold time")
    void focus_block_is_not_a_wall() {
        insertTimeBlock("Focus accounting", "IN_PROGRESS", "FOCUS", at(9, 0), at(10, 0));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("a row left behind in the frozen core_time_block table no longer walls")
    void frozen_table_row_is_not_a_wall() {
        UUID anchor = insertTask("Legacy anchor");
        insertFrozenBlock(anchor, at(9, 0), at(11, 0));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("an open-ended block still yields a strictly positive wall (one-minute stub)")
    void open_ended_block_yields_a_minimal_wall() {
        UUID block = insertTimeBlock("Open ended", "IN_PROGRESS", "USER", at(9, 0), null);

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END))
            .singleElement()
            .satisfies(wall -> {
                assertThat(wall.executableId()).isEqualTo(block);
                assertThat(wall.end()).isEqualTo(at(9, 1));
            });
    }

    @Test
    @DisplayName("a block outside the planning window is not a wall")
    void block_outside_the_window_is_not_a_wall() {
        insertTimeBlock("Yesterday", "DONE", "PLANNER",
            at(9, 0).minusDays(1), at(11, 0).minusDays(1));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("an activity standing on the day is a wall: movable in hour never meant weightless")
    void a_standing_activity_is_a_wall() {
        // The production row: «Desayunar», an ACTIVITY holding 10:30-11:30. It is not containable, so it
        // never was a candidate; it was not read as occupancy either, so the day was laid over it.
        UUID breakfast = insertCommitment("Desayunar", "ACTIVITY", "TODO", at(10, 30), at(11, 30));

        List<OccupiedInterval> walls = repository.loadOccupiedIntervals(USER, DAY_START, DAY_END);

        assertThat(walls).singleElement().satisfies(wall -> {
            assertThat(wall.executableId()).isEqualTo(breakfast);
            assertThat(wall.start()).isEqualTo(at(10, 30));
            assertThat(wall.end()).isEqualTo(at(11, 30));
            // Not read-only: the rescue may still send it a different hour of its own day (D9).
            assertThat(wall.readOnlyAgenda()).isFalse();
        });
    }

    @Test
    @DisplayName("a study session walls exactly like an activity — both own a calendar window")
    void a_study_session_is_a_wall() {
        UUID session = insertCommitment("Repaso", "LEARNING_SESSION", "IN_PROGRESS",
            at(16, 0), at(17, 0));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END))
            .extracting(OccupiedInterval::executableId)
            .containsExactly(session);
    }

    @Test
    @DisplayName("every unsettled state of a commitment walls, including the ones no whitelist would list")
    void unsettled_commitments_all_wall() {
        // Written as an exclusion, not a whitelist: a row ingested from Notion or the calendar arrives
        // in whatever state its origin gives it, and a whitelist is what once made those invisible.
        UUID todo = insertCommitment("Todo", "ACTIVITY", "TODO", at(8, 0), at(9, 0));
        UUID running = insertCommitment("Running", "ACTIVITY", "IN_PROGRESS", at(9, 0), at(10, 0));
        UUID waiting = insertCommitment("Waiting", "ACTIVITY", "WAITING", at(10, 0), at(11, 0));
        UUID planned = insertCommitment("Planned", "LEARNING_SESSION", "PLANNED", at(11, 0), at(12, 0));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END))
            .extracting(OccupiedInterval::executableId)
            .containsExactlyInAnyOrder(todo, running, waiting, planned);
    }

    @Test
    @DisplayName("a settled commitment releases its hour: what is done or failed does not occupy the day")
    void a_settled_commitment_is_not_a_wall() {
        // The one place a commitment parts ways with a block, which walls in every state: a block that
        // is closed is time that has already gone by, whereas the user can settle a commitment ahead of
        // its hour — and the hour it no longer needs goes back to the day.
        insertCommitment("Done", "ACTIVITY", "DONE", at(9, 0), at(10, 0));
        insertCommitment("Failed", "LEARNING_SESSION", "FAILED", at(11, 0), at(12, 0));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("a system-generated commitment never walls: it accounts for work, it reserves nothing")
    void a_system_generated_commitment_is_not_a_wall() {
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, system_generated, start_time, end_time)
            VALUES (?, ?, 'Snapshot', 'ACTIVITY', 'TODO', true, ?, ?)
            """, UUID.randomUUID(), USER, at(9, 0), at(10, 0));

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("a commitment without a real window is not a wall — there is no time to hold")
    void a_windowless_commitment_is_not_a_wall() {
        insertCommitment("No window", "ACTIVITY", "TODO", at(9, 0), null);

        assertThat(repository.loadOccupiedIntervals(USER, DAY_START, DAY_END)).isEmpty();
    }

    // ─── the sleep the day is ordered around ───────────────────────────────────

    @Test
    @DisplayName("the day reads every session of the row, night and nap alike, in clock order")
    void recent_sleep_reads_the_sessions_of_the_row() {
        // A row is stamped with its MAIN session's hours; the nap only exists inside the array, which is
        // exactly why the read cannot stop at the two instant columns.
        insertSleepRecord(at(6, 30).minusDays(1), at(6, 30), """
            {"sessions":[
              {"start":"2026-08-06T22:30:00Z","end":"2026-08-07T06:30:00Z","asleep_seconds":26400},
              {"start":"2026-08-07T09:20:00Z","end":"2026-08-07T11:00:00Z","asleep_seconds":5400}]}""");

        assertThat(repository.loadRecentSleepSessions(USER, NOON))
            .extracting(session -> session.start().toString())
            .containsExactly("2026-08-06T22:30Z", "2026-08-07T09:20Z");
    }

    @Test
    @DisplayName("a row written before the sessions array existed still reports its own hours")
    void recent_sleep_falls_back_to_the_rows_own_hours() {
        // Every row already in production predates the array; losing them would leave the model blind on
        // exactly the days it most needs the context.
        insertSleepRecord(at(23, 0).minusDays(1), at(6, 0), "{\"in_bed_seconds\":0}");

        assertThat(repository.loadRecentSleepSessions(USER, NOON)).singleElement().satisfies(session -> {
            assertThat(session.start()).isEqualTo(at(23, 0).minusDays(1));
            assertThat(session.end()).isEqualTo(at(6, 0));
        });
    }

    @Test
    @DisplayName("a nap survives its row falling out of the lookback; sleep older than a day does not")
    void recent_sleep_is_bounded_by_when_the_session_ended_not_the_row() {
        // The row is stamped 40 h back, outside the day the model is being told about — but it carries a
        // nap from this morning, and that nap is the whole point. A read bounded by the row's hours would
        // have dropped it with the row.
        insertSleepRecord(NOON.minusHours(48), NOON.minusHours(40), """
            {"sessions":[
              {"start":"2026-08-05T22:00:00Z","end":"2026-08-06T04:00:00Z","asleep_seconds":20000},
              {"start":"2026-08-07T09:00:00Z","end":"2026-08-07T10:00:00Z","asleep_seconds":3000}]}""");

        assertThat(repository.loadRecentSleepSessions(USER, NOON))
            .extracting(session -> session.start().toString())
            .containsExactly("2026-08-07T09:00Z");
    }

    // ─── goal-selector signals (F1 hysteresis + release valve) ─────────────────

    @Test
    @DisplayName("hysteresis: a lead measure held by yesterday's PLANNER block reports it")
    void lead_measure_contained_yesterday_feeds_the_hysteresis_flag() {
        UUID mci = insertActiveMci();
        UUID leadMeasure = insertLeadMeasure(mci);
        UUID yesterdayBlock = insertTimeBlock("Yesterday's window", "DONE", "PLANNER",
            at(9, 0).minusDays(1), at(10, 0).minusDays(1));
        contain(leadMeasure, yesterdayBlock);

        MciWig wig = onlyWig();

        assertThat(wig.leadMeasureId()).isEqualTo(leadMeasure);
        assertThat(wig.receivedBlockYesterday()).isTrue();
        // One day since the block: the block-less streak is zero.
        assertThat(wig.degradedDaysWithoutBlock()).isZero();
    }

    @Test
    @DisplayName("release valve: the block-less streak counts the days since the last PLANNER block")
    void streak_counts_days_since_the_last_planner_block() {
        UUID mci = insertActiveMci();
        UUID leadMeasure = insertLeadMeasure(mci);
        UUID oldBlock = insertTimeBlock("Three days ago", "DONE", "PLANNER",
            at(9, 0).minusDays(3), at(10, 0).minusDays(3));
        contain(leadMeasure, oldBlock);

        MciWig wig = onlyWig();

        assertThat(wig.receivedBlockYesterday()).isFalse();
        assertThat(wig.degradedDaysWithoutBlock()).isEqualTo(2);
    }

    @Test
    @DisplayName("release valve: a lead measure that never held a block saturates the streak at the bound")
    void streak_saturates_without_any_block() {
        UUID mci = insertActiveMci();
        insertLeadMeasure(mci);

        MciWig wig = onlyWig();

        assertThat(wig.receivedBlockYesterday()).isFalse();
        assertThat(wig.degradedDaysWithoutBlock()).isEqualTo(STREAK_BOUND);
    }

    @Test
    @DisplayName("a FOCUS block held by the lead measure feeds neither signal (only PLANNER blocks do)")
    void focus_containment_does_not_feed_the_signals() {
        UUID mci = insertActiveMci();
        UUID leadMeasure = insertLeadMeasure(mci);
        UUID focusBlock = insertTimeBlock("Focus", "DONE", "FOCUS",
            at(9, 0).minusDays(1), at(10, 0).minusDays(1));
        contain(leadMeasure, focusBlock);

        MciWig wig = onlyWig();

        assertThat(wig.receivedBlockYesterday()).isFalse();
        assertThat(wig.degradedDaysWithoutBlock()).isEqualTo(STREAK_BOUND);
    }

    @Test
    @DisplayName("a block left in the frozen table feeds neither signal")
    void frozen_table_block_does_not_feed_the_signals() {
        UUID mci = insertActiveMci();
        UUID leadMeasure = insertLeadMeasure(mci);
        insertFrozenBlock(leadMeasure, at(9, 0).minusDays(1), at(10, 0).minusDays(1));

        MciWig wig = onlyWig();

        assertThat(wig.receivedBlockYesterday()).isFalse();
        assertThat(wig.degradedDaysWithoutBlock()).isEqualTo(STREAK_BOUND);
    }

    // ─── fixtures ──────────────────────────────────────────────────────────────

    private MciWig onlyWig() {
        List<MciWig> portfolio = repository.loadWigPortfolio(USER, NOON);
        assertThat(portfolio).hasSize(1);
        return portfolio.get(0);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, java.time.LocalTime.of(hour, minute), UTC);
    }

    private UUID insertTimeBlock(String name, String status, String origin,
                                 OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', ?, ?, ?, ?)
            """, id, USER, name, status, origin, start, end);
        return id;
    }

    /**
     * A block as it is really born when the user creates it: from Notion, through the same ingestion
     * every other executable takes — so it carries no origin and the default {@code TODO} status.
     */
    private UUID insertUserBlock(String name, OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', 'TODO', ?, ?)
            """, id, USER, name, start, end);
        return id;
    }

    /**
     * A commitment as it arrives from Notion or the calendar: a typed executable that owns a window of
     * its own and carries whatever status its origin gave it.
     */
    private UUID insertCommitment(String name, String type, String status,
                                  OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, USER, name, type, status, start, end);
        return id;
    }

    /** A scored device sleep row: the two instant columns hold the main session, {@code stages} the rest. */
    private void insertSleepRecord(OffsetDateTime start, OffsetDateTime end, String stagesJson) {
        jdbcTemplate.update("""
            INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, stages)
            VALUES (?, ?, ?, ?, 74, ?::jsonb)
            """, UUID.randomUUID(), USER, start, end, stagesJson);
    }

    private UUID insertTask(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', 0.5)
            """, id, USER, name);
        return id;
    }

    private void contain(UUID memberId, UUID blockId) {
        jdbcTemplate.update("UPDATE core_executable SET container_block_id = ? WHERE id = ?",
            blockId, memberId);
    }

    private void insertFrozenBlock(UUID anchorId, OffsetDateTime start, OffsetDateTime end) {
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin)
            VALUES (?, ?, ?, ?, 'PLANNED', 'PLANNER')
            """, UUID.randomUUID(), anchorId, start, end);
    }

    private UUID insertActiveMci() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, start_date, end_date)
            VALUES (?, ?, 'MCI', 'MCI', 'ACTIVE', ?, ?)
            """, id, USER, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        return id;
    }

    private UUID insertLeadMeasure(UUID cycleId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'Lead measure', 'LEAD_MEASURE', 'TODO', 0.5)
            """, id, USER, cycleId);
        return id;
    }
}
