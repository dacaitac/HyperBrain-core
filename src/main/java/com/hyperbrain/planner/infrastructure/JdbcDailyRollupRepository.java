package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.DailyAdherenceReport;
import com.hyperbrain.planner.domain.port.out.DailyRollupRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.UUID;

/**
 * JDBC adapter for {@link DailyRollupRepository} backed by {@code plnr_daily_rollup} (ADR-025 D4).
 * Upserts the projection on the primary key {@code (user_id, agenda_date)} so re-rolling the same
 * local day — a replay or a late-settling block — refreshes the row instead of failing or
 * duplicating.
 *
 * <p>The record's {@code zone} is not persisted: it is only the context used upstream to derive the
 * local day, and the user's timezone already lives in {@code sys_user.timezone}. {@code materialized_at}
 * is stamped by the database (column default on insert, {@code now()} on update). {@code ritual_completed}
 * is the record's boxed {@code Boolean} (a partial proxy for the ADR-018 ritual) and the one nullable
 * measure column; the driver binds a null through unchanged.
 */
@Repository
class JdbcDailyRollupRepository implements DailyRollupRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO plnr_daily_rollup
            (user_id, agenda_date, blocks_planned, blocks_executed, adherence,
             wig_hit, ritual_completed, replan_count, abandoned)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (user_id, agenda_date) DO UPDATE SET
            blocks_planned   = EXCLUDED.blocks_planned,
            blocks_executed  = EXCLUDED.blocks_executed,
            adherence        = EXCLUDED.adherence,
            wig_hit          = EXCLUDED.wig_hit,
            ritual_completed = EXCLUDED.ritual_completed,
            replan_count     = EXCLUDED.replan_count,
            abandoned        = EXCLUDED.abandoned,
            materialized_at  = now()
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcDailyRollupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsert(UUID userId, DailyAdherenceReport report) {
        jdbcTemplate.update(UPSERT_SQL,
            userId,
            Date.valueOf(report.date()),
            report.blocksPlanned(),
            report.blocksExecuted(),
            report.adherence(),
            report.wigHit(),
            report.ritualCompleted(),
            report.replanCount(),
            report.abandoned());
    }
}
