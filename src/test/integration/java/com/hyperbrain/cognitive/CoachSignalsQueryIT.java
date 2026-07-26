package com.hyperbrain.cognitive;

import com.hyperbrain.cognitive.domain.model.CoachSignals;
import com.hyperbrain.cognitive.domain.port.out.DailyAdherenceQuery;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies {@link DailyAdherenceQuery} against a real PostgreSQL {@code plnr_daily_rollup} projection
 * (ADR-025 D4): the cognitive coach voice reads the latest day's hard signals and the derived WIG streak
 * from the same projection the iOS Scoreboard consumes, without touching the planner's infrastructure.
 */
@IntegrationTest
@DisplayName("DailyAdherenceQuery — latest signals + WIG streak from plnr_daily_rollup (ADR-029 D3)")
class CoachSignalsQueryIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 24);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DailyAdherenceQuery query;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM plnr_daily_rollup");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("no rollup yet → empty")
    void empty_when_no_rollup() {
        assertThat(query.latestSignals(USER)).isEmpty();
    }

    @Test
    @DisplayName("returns the latest day's hard signals")
    void returns_latest_day_signals() {
        insertRollup(DAY.minusDays(1), false, false, 0.4);
        insertRollup(DAY, true, false, 0.85);

        CoachSignals signals = query.latestSignals(USER).orElseThrow();

        assertThat(signals.date()).isEqualTo(DAY);
        assertThat(signals.wigHit()).isTrue();
        assertThat(signals.abandoned()).isFalse();
        assertThat(signals.adherence()).isCloseTo(0.85, within(1e-9));
    }

    @Test
    @DisplayName("WIG streak counts consecutive hit days ending at the latest day")
    void streak_counts_consecutive_hits() {
        insertRollup(DAY.minusDays(2), true, false, 0.9);
        insertRollup(DAY.minusDays(1), true, false, 0.9);
        insertRollup(DAY, true, false, 0.9);

        assertThat(query.latestSignals(USER).orElseThrow().wigStreak()).isEqualTo(3);
    }

    @Test
    @DisplayName("a missed-WIG day breaks the streak")
    void miss_breaks_the_streak() {
        insertRollup(DAY.minusDays(2), true, false, 0.9);
        insertRollup(DAY.minusDays(1), false, false, 0.3); // miss breaks it
        insertRollup(DAY, true, false, 0.9);

        assertThat(query.latestSignals(USER).orElseThrow().wigStreak()).isEqualTo(1);
    }

    @Test
    @DisplayName("a calendar gap (a day with no rollup) breaks the streak")
    void gap_breaks_the_streak() {
        insertRollup(DAY.minusDays(3), true, false, 0.9);
        // DAY-2 and DAY-1 missing → gap
        insertRollup(DAY, true, false, 0.9);

        assertThat(query.latestSignals(USER).orElseThrow().wigStreak()).isEqualTo(1);
    }

    @Test
    @DisplayName("a latest day that missed the WIG yields a zero streak")
    void latest_miss_is_zero_streak() {
        insertRollup(DAY.minusDays(1), true, false, 0.9);
        insertRollup(DAY, false, true, 0.2);

        Optional<CoachSignals> signals = query.latestSignals(USER);

        assertThat(signals).isPresent();
        assertThat(signals.get().wigHit()).isFalse();
        assertThat(signals.get().abandoned()).isTrue();
        assertThat(signals.get().wigStreak()).isZero();
    }

    private void insertRollup(LocalDate day, boolean wigHit, boolean abandoned, double adherence) {
        jdbcTemplate.update("""
            INSERT INTO plnr_daily_rollup
                (user_id, agenda_date, blocks_planned, blocks_executed, adherence,
                 wig_hit, ritual_completed, replan_count, abandoned)
            VALUES (?, ?, 4, 3, ?, ?, true, 0, ?)
            """, USER, Date.valueOf(day), adherence, wigHit, abandoned);
    }
}
