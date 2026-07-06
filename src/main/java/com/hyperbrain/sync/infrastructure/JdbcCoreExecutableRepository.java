package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link CoreExecutableRepository}. Writes only the columns populated by
 * the sync pipeline; all other columns keep their DDL defaults.
 */
@Repository
class JdbcCoreExecutableRepository implements CoreExecutableRepository {

    private static final String INSERT_SQL = """
        INSERT INTO core_executable
            (id, user_id, name, type, status, start_time, end_time, source_calendar)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE core_executable
        SET name = ?, status = ?, start_time = ?, end_time = ?, source_calendar = ?
        WHERE id = ?
        """;

    private static final String FIND_BY_ID_SQL = """
        SELECT id, user_id, name, type, status, start_time, end_time, source_calendar
        FROM core_executable
        WHERE id = ?
        """;

    private static final String DELETE_BY_ID_SQL =
        "DELETE FROM core_executable WHERE id = ?";

    private static final RowMapper<CoreExecutable> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp startTs = rs.getTimestamp("start_time");
        Timestamp endTs   = rs.getTimestamp("end_time");
        return new CoreExecutable(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("status"),
            startTs != null ? startTs.toInstant().atOffset(java.time.ZoneOffset.UTC) : null,
            endTs   != null ? endTs.toInstant().atOffset(java.time.ZoneOffset.UTC)   : null,
            rs.getString("source_calendar"));
    };

    private final JdbcTemplate jdbcTemplate;

    JdbcCoreExecutableRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(CoreExecutable e) {
        jdbcTemplate.update(INSERT_SQL,
            e.id(), e.userId(), e.name(), e.type(), e.status(),
            toTimestamp(e.startTime()), toTimestamp(e.endTime()),
            e.sourceCalendar());
    }

    @Override
    public void update(CoreExecutable e) {
        jdbcTemplate.update(UPDATE_SQL,
            e.name(), e.status(),
            toTimestamp(e.startTime()), toTimestamp(e.endTime()),
            e.sourceCalendar(),
            e.id());
    }

    @Override
    public Optional<CoreExecutable> findById(UUID id) {
        List<CoreExecutable> rows = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void deleteById(UUID id) {
        jdbcTemplate.update(DELETE_BY_ID_SQL, id);
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }
}
