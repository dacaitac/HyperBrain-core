package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.DailyBlockObservation;
import com.hyperbrain.planner.domain.port.out.DailyTelemetryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link DailyTelemetryRepository}. Reads the H0 rollup inputs straight from the
 * core tables (like the sibling {@code JdbcPlannerStateRepository}) with the day boundaries projected
 * into the user's timezone via {@code AT TIME ZONE}, so the reads are a pure function of the
 * requested local day, never the DB server clock.
 */
@Repository
public class JdbcDailyTelemetryRepository implements DailyTelemetryRepository {

    /**
     * The day's planner blocks with their WIG flag and settled execution signal. A block reserves the
     * WIG when its executable is the F1 lead measure ({@code type = 'LEAD_MEASURE'}).
     */
    private static final String PLANNER_BLOCKS_SQL = """
        SELECT b.actual_duration_minutes            AS actual_minutes,
               (e.type = 'LEAD_MEASURE')            AS wig
        FROM core_time_block b
        JOIN core_executable e ON e.id = b.executable_id
        WHERE e.user_id = ?
          AND b.origin = 'PLANNER'
          AND (b.date_start AT TIME ZONE ?)::date = ?::date
        """;

    /** Replan commands processed on the day (dedup log keyed by command_id, event_type carries the type). */
    private static final String REPLAN_COUNT_SQL = """
        SELECT count(*)
        FROM processed_message
        WHERE event_type = 'REPLAN_AGENDA'
          AND (processed_at AT TIME ZONE ?)::date = ?::date
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDailyTelemetryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DailyBlockObservation> loadPlannerBlockObservations(UUID userId, LocalDate day, ZoneId zone) {
        return jdbcTemplate.query(
            PLANNER_BLOCKS_SQL,
            (rs, rowNum) -> new DailyBlockObservation(
                rs.getBoolean("wig"),
                (Integer) rs.getObject("actual_minutes")),
            userId, zone.getId(), day.toString());
    }

    @Override
    public int countReplans(LocalDate day, ZoneId zone) {
        Integer count = jdbcTemplate.queryForObject(
            REPLAN_COUNT_SQL, Integer.class, zone.getId(), day.toString());
        return count == null ? 0 : count;
    }
}
