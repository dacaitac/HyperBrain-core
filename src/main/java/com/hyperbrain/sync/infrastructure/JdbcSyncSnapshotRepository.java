package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link SyncSnapshotRepository}: read-only joins over {@code core_executable}
 * (+ its {@code core_execution_profile}) and {@code core_cycle} for the Notion write-back.
 */
@Repository
class JdbcSyncSnapshotRepository implements SyncSnapshotRepository {

    private static final String FIND_EXECUTABLE_SQL = """
        SELECT e.id, e.user_id, e.parent_id, e.cycle_id, e.name, e.description, e.type, e.status,
               e.priority_score, e.urgency_score, e.effort_score, e.start_time, e.end_time,
               p.energy_drain, p.mental_load, p.impact
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.id = ?
        """;

    private static final String FIND_CYCLE_SQL = """
        SELECT id, user_id, name, type, status, start_date, end_date
        FROM core_cycle
        WHERE id = ?
        """;

    private static final RowMapper<ExecutableSnapshot> EXECUTABLE_MAPPER = (rs, rowNum) -> {
        Timestamp startTs = rs.getTimestamp("start_time");
        Timestamp endTs = rs.getTimestamp("end_time");
        return new ExecutableSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            rs.getObject("cycle_id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("type"),
            rs.getString("status"),
            rs.getObject("priority_score", Double.class),
            rs.getObject("urgency_score", Double.class),
            rs.getObject("effort_score", Double.class),
            startTs != null ? startTs.toInstant().atOffset(ZoneOffset.UTC) : null,
            endTs != null ? endTs.toInstant().atOffset(ZoneOffset.UTC) : null,
            rs.getObject("energy_drain", Integer.class),
            rs.getObject("mental_load", Integer.class),
            rs.getObject("impact", Integer.class));
    };

    private static final RowMapper<CycleSnapshot> CYCLE_MAPPER = (rs, rowNum) -> {
        Date startDate = rs.getDate("start_date");
        Date endDate = rs.getDate("end_date");
        return new CycleSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("status"),
            startDate != null ? startDate.toLocalDate() : null,
            endDate != null ? endDate.toLocalDate() : null);
    };

    private final JdbcTemplate jdbcTemplate;

    JdbcSyncSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ExecutableSnapshot> findExecutable(UUID id) {
        List<ExecutableSnapshot> rows = jdbcTemplate.query(FIND_EXECUTABLE_SQL, EXECUTABLE_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<CycleSnapshot> findCycle(UUID id) {
        List<CycleSnapshot> rows = jdbcTemplate.query(FIND_CYCLE_SQL, CYCLE_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
