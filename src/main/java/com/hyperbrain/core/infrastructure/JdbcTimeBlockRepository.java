package com.hyperbrain.core.infrastructure;

import com.hyperbrain.core.domain.model.TimeBlock;
import com.hyperbrain.core.domain.model.TimeBlockOrigin;
import com.hyperbrain.core.domain.model.TimeBlockStatus;
import com.hyperbrain.core.domain.port.out.TimeBlockRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link TimeBlockRepository}. The expiry lock uses
 * {@code FOR UPDATE SKIP LOCKED} (same recipe as the outbox drain) so the cron and a
 * concurrent focus-switch settlement never block each other; the conditional settle keeps the
 * operation idempotent.
 */
@Repository
class JdbcTimeBlockRepository implements TimeBlockRepository {

    private static final String COLUMNS =
        "id, executable_id, date_start, date_end, status, origin, "
            + "planned_minutes, actual_duration_minutes, settled_at, created_at";

    private static final String FIND_ACTIVE_SQL = """
        SELECT %s
        FROM core_time_block
        WHERE executable_id = ? AND status = 'ACTIVE'
        ORDER BY date_start DESC
        LIMIT 1
        """.formatted(COLUMNS);

    private static final String INSERT_SQL = """
        INSERT INTO core_time_block
            (id, executable_id, date_start, date_end, status, origin,
             planned_minutes, actual_duration_minutes, settled_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String LOCK_OPEN_EXPIRED_SQL = """
        SELECT %s
        FROM core_time_block
        WHERE status IN ('PLANNED', 'ACTIVE') AND date_end IS NOT NULL AND date_end < ?
        ORDER BY date_end
        FOR UPDATE SKIP LOCKED
        """.formatted(COLUMNS);

    private static final String SETTLE_SQL = """
        UPDATE core_time_block
        SET status = ?, actual_duration_minutes = ?, settled_at = ?
        WHERE id = ? AND status IN ('PLANNED', 'ACTIVE')
        """;

    private static final RowMapper<TimeBlock> ROW_MAPPER = (rs, rowNum) -> new TimeBlock(
        rs.getObject("id", UUID.class),
        rs.getObject("executable_id", UUID.class),
        toOffset(rs.getTimestamp("date_start")),
        toOffset(rs.getTimestamp("date_end")),
        TimeBlockStatus.valueOf(rs.getString("status")),
        TimeBlockOrigin.valueOf(rs.getString("origin")),
        rs.getObject("planned_minutes", Integer.class),
        rs.getObject("actual_duration_minutes", Integer.class),
        toOffset(rs.getTimestamp("settled_at")),
        toOffset(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    JdbcTimeBlockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TimeBlock> findActiveBlock(UUID executableId) {
        List<TimeBlock> rows = jdbcTemplate.query(FIND_ACTIVE_SQL, ROW_MAPPER, executableId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void insert(TimeBlock block) {
        jdbcTemplate.update(INSERT_SQL,
            block.id(), block.executableId(),
            toTimestamp(block.dateStart()), toTimestamp(block.dateEnd()),
            block.status().name(), block.origin().name(),
            block.plannedMinutes(), block.actualDurationMinutes(),
            toTimestamp(block.settledAt()), toTimestamp(block.createdAt()));
    }

    @Override
    public List<TimeBlock> lockOpenExpired(OffsetDateTime now) {
        return jdbcTemplate.query(LOCK_OPEN_EXPIRED_SQL, ROW_MAPPER, toTimestamp(now));
    }

    @Override
    public boolean settle(UUID blockId, TimeBlockStatus finalStatus,
                          Integer actualDurationMinutes, OffsetDateTime settledAt) {
        return jdbcTemplate.update(SETTLE_SQL,
            finalStatus.name(), actualDurationMinutes, toTimestamp(settledAt), blockId) > 0;
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
