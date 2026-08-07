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
    @DisplayName("every lifecycle state of a real block walls: open, executing and already closed")
    void open_and_closed_blocks_all_wall() {
        UUID planned = insertTimeBlock("Planned", "PLANNED", "PLANNER", at(8, 0), at(9, 0));
        UUID running = insertTimeBlock("Running", "IN_PROGRESS", "USER", at(9, 30), at(10, 30));
        UUID done = insertTimeBlock("Done", "DONE", "PLANNER", at(11, 0), at(12, 0));
        // The expiry sweep closes an unfulfilled block: its window is in the past, and the past is
        // never rewritten, so it keeps walling (the legacy read dropped this state).
        UUID failed = insertTimeBlock("Failed", "FAILED", "PLANNER", at(13, 0), at(14, 0));

        List<OccupiedInterval> walls = repository.loadOccupiedIntervals(USER, DAY_START, DAY_END);

        assertThat(walls).extracting(OccupiedInterval::executableId)
            .containsExactlyInAnyOrder(planned, running, done, failed);
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
