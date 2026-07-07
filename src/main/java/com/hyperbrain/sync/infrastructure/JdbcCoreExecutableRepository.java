package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
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
            (id, user_id, name, description, type, status, start_time, end_time, source_calendar)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE core_executable
        SET name = ?, description = ?, status = ?, start_time = ?, end_time = ?, source_calendar = ?
        WHERE id = ?
        """;

    private static final String FIND_BY_ID_SQL = """
        SELECT id, user_id, name, description, type, status, start_time, end_time, source_calendar
        FROM core_executable
        WHERE id = ?
        """;

    private static final String DELETE_BY_ID_SQL =
        "DELETE FROM core_executable WHERE id = ?";

    private static final String UPSERT_SQL = """
        INSERT INTO core_executable
            (id, user_id, parent_id, cycle_id, name, description, type, status,
             priority_score, urgency_score, effort_score, start_time, end_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            parent_id      = EXCLUDED.parent_id,
            cycle_id       = EXCLUDED.cycle_id,
            name           = EXCLUDED.name,
            description    = EXCLUDED.description,
            type           = EXCLUDED.type,
            status         = EXCLUDED.status,
            priority_score = EXCLUDED.priority_score,
            urgency_score  = EXCLUDED.urgency_score,
            effort_score   = EXCLUDED.effort_score,
            start_time     = EXCLUDED.start_time,
            end_time       = EXCLUDED.end_time
        """;

    private static final String UPSERT_PROFILE_SQL = """
        INSERT INTO core_execution_profile (executable_id, energy_drain, mental_load, impact)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (executable_id) DO UPDATE SET
            energy_drain = EXCLUDED.energy_drain,
            mental_load  = EXCLUDED.mental_load,
            impact       = EXCLUDED.impact
        """;

    private static final RowMapper<CoreExecutable> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp startTs = rs.getTimestamp("start_time");
        Timestamp endTs   = rs.getTimestamp("end_time");
        return new CoreExecutable(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
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
            e.id(), e.userId(), e.name(), e.description(), e.type(), e.status(),
            toTimestamp(e.startTime()), toTimestamp(e.endTime()),
            e.sourceCalendar());
    }

    @Override
    public void update(CoreExecutable e) {
        jdbcTemplate.update(UPDATE_SQL,
            e.name(), e.description(), e.status(),
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

    @Override
    public void upsert(ExecutableSnapshot s) {
        jdbcTemplate.update(UPSERT_SQL,
            s.id(), s.userId(), s.parentId(), s.cycleId(), s.name(), s.description(),
            s.type(), s.status(), s.priorityScore(), s.urgencyScore(), s.effortScore(),
            toTimestamp(s.startTime()), toTimestamp(s.endTime()));
        jdbcTemplate.update(UPSERT_PROFILE_SQL,
            s.id(), s.energyDrain(), s.mentalLoad(), s.impact());
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }
}
