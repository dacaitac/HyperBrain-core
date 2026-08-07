package com.hyperbrain.core.infrastructure;

import com.hyperbrain.core.domain.model.TimeBlockExecutable;
import com.hyperbrain.core.domain.port.out.TimeBlockExecutableRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link TimeBlockExecutableRepository} (ADR-039). The expiry lock uses
 * {@code FOR UPDATE SKIP LOCKED} (same recipe as the outbox drain) so the cron and a concurrent
 * focus-switch settlement never block each other; the conditional settle keeps the operation
 * idempotent.
 */
@Repository
class JdbcTimeBlockExecutableRepository implements TimeBlockExecutableRepository {

    private static final String COLUMNS =
        "id, user_id, parent_id, start_time, end_time, status, origin, "
            + "actual_duration_minutes, last_completed_at";

    /**
     * The executing block accounting for a task: its FOCUS child ({@code parent_id}) or its
     * current container ({@code container_block_id}) while that container runs.
     */

    private static final String LOCK_OPEN_EXPIRED_SQL = """
        SELECT %s
        FROM core_executable b
        WHERE b.type = 'TIME_BLOCK'
          AND b.status IN ('PLANNED', 'IN_PROGRESS')
          AND b.end_time IS NOT NULL AND b.end_time < ?
        ORDER BY b.end_time
        FOR UPDATE SKIP LOCKED
        """.formatted(COLUMNS);

    private static final String SETTLE_SQL = """
        UPDATE core_executable
        SET status = ?, actual_duration_minutes = ?, last_completed_at = ?
        WHERE id = ? AND type = 'TIME_BLOCK' AND status IN ('PLANNED', 'IN_PROGRESS')
        """;

    private static final RowMapper<TimeBlockExecutable> ROW_MAPPER = (rs, rowNum) ->
        new TimeBlockExecutable(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            toOffset(rs.getTimestamp("start_time")),
            toOffset(rs.getTimestamp("end_time")),
            rs.getString("status"),
            rs.getString("origin"),
            rs.getObject("actual_duration_minutes", Integer.class),
            toOffset(rs.getTimestamp("last_completed_at")));

    private final JdbcTemplate jdbcTemplate;

    JdbcTimeBlockExecutableRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TimeBlockExecutable> lockOpenExpired(OffsetDateTime now) {
        return jdbcTemplate.query(LOCK_OPEN_EXPIRED_SQL, ROW_MAPPER, toTimestamp(now));
    }

    @Override
    public boolean settle(UUID blockId, String finalStatus,
                          Integer actualDurationMinutes, OffsetDateTime settledAt) {
        return jdbcTemplate.update(SETTLE_SQL,
            finalStatus, actualDurationMinutes, toTimestamp(settledAt), blockId) > 0;
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
