package com.hyperbrain.cognitive.infrastructure;

import com.hyperbrain.cognitive.domain.model.CoachSignals;
import com.hyperbrain.cognitive.domain.port.out.DailyAdherenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link DailyAdherenceQuery}, reading the planner's {@code plnr_daily_rollup} projection
 * (ADR-025 D4). A read-only SELECT — the cognitive module never writes another module's state (ArchUnit) —
 * that lifts the latest day's hard signals and derives the WIG streak in memory.
 *
 * <p><b>WIG streak.</b> The rollup stores no streak column; it is derived here as the count of consecutive
 * <em>calendar</em> days, ending at the latest rolled-up day, whose {@code wig_hit} is true. A missing day
 * (no rollup) or a {@code wig_hit=false} day breaks the streak — a gap is not a hit. A bounded recent
 * window is scanned (streaks beyond it are cosmetically capped, never wrong within the MVP horizon).
 */
@Repository
class JdbcDailyAdherenceQuery implements DailyAdherenceQuery {

    /** Recent days scanned to derive the streak — ample for the 14-day MVP horizon. */
    private static final int STREAK_WINDOW_DAYS = 90;

    private static final String LATEST_SQL = """
        SELECT agenda_date, wig_hit, abandoned, adherence
        FROM plnr_daily_rollup
        WHERE user_id = ?
        ORDER BY agenda_date DESC
        LIMIT ?
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcDailyAdherenceQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CoachSignals> latestSignals(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        List<Row> rows = jdbcTemplate.query(LATEST_SQL,
            (rs, i) -> new Row(rs.getObject("agenda_date", LocalDate.class),
                rs.getBoolean("wig_hit"), rs.getBoolean("abandoned"), rs.getDouble("adherence")),
            userId, STREAK_WINDOW_DAYS);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row latest = rows.get(0);
        int streak = wigStreak(rows);
        return Optional.of(new CoachSignals(
            userId, latest.date(), latest.wigHit(), latest.abandoned(), streak, latest.adherence()));
    }

    /**
     * Consecutive WIG-hit days ending at the latest row: walks the descending, date-ordered rows and stops
     * at the first non-hit day or the first calendar gap (a day with no rollup breaks contiguity).
     */
    private static int wigStreak(List<Row> descendingByDate) {
        int streak = 0;
        LocalDate expected = descendingByDate.get(0).date();
        for (Row row : descendingByDate) {
            if (!row.date().equals(expected) || !row.wigHit()) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    private record Row(LocalDate date, boolean wigHit, boolean abandoned, double adherence) {
    }
}
