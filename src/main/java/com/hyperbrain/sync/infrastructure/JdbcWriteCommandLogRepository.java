package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link WriteCommandLogRepository} over {@code sync_write_commands}.
 * {@code upsertPending} uses {@code ON CONFLICT} because retried outbox drains re-emit the
 * same deterministic {@code command_id}.
 */
@Repository
class JdbcWriteCommandLogRepository implements WriteCommandLogRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO sync_write_commands
            (command_id, user_id, local_id, command_type, operation, entity_id, payload, status)
        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING')
        ON CONFLICT (command_id) DO UPDATE
        SET entity_id = EXCLUDED.entity_id, payload = EXCLUDED.payload
        """;

    private static final String FIND_BY_ID_SQL = """
        SELECT command_id, user_id, local_id, command_type, operation, entity_id, payload, status
        FROM sync_write_commands
        WHERE command_id = ?
        """;

    private static final String FIND_PENDING_CREATE_SQL = """
        SELECT command_id, user_id, local_id, command_type, operation, entity_id, payload, status
        FROM sync_write_commands
        WHERE local_id = ? AND operation = 'CREATED' AND status = 'PENDING'
        ORDER BY created_at DESC
        LIMIT 1
        """;

    private static final String MARK_APPLIED_SQL = """
        UPDATE sync_write_commands
        SET status = 'APPLIED', entity_id = ?, error = NULL, resolved_at = ?
        WHERE command_id = ?
        """;

    private static final String MARK_FAILED_SQL = """
        UPDATE sync_write_commands
        SET status = 'FAILED', error = ?, resolved_at = ?
        WHERE command_id = ?
        """;

    private static final RowMapper<PendingWriteCommand> ROW_MAPPER = (rs, rowNum) -> new PendingWriteCommand(
        rs.getObject("command_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("local_id", UUID.class),
        CommandType.valueOf(rs.getString("command_type")),
        Operation.valueOf(rs.getString("operation")),
        rs.getString("entity_id"),
        rs.getString("payload"),
        rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    JdbcWriteCommandLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsertPending(PendingWriteCommand c) {
        jdbcTemplate.update(UPSERT_SQL,
            c.commandId(), c.userId(), c.localId(),
            c.commandType().name(), c.operation().name(),
            c.entityId(), c.payloadJson());
    }

    @Override
    public Optional<PendingWriteCommand> findById(UUID commandId) {
        List<PendingWriteCommand> rows = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, commandId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<PendingWriteCommand> findPendingCreateByLocalId(UUID localId) {
        List<PendingWriteCommand> rows = jdbcTemplate.query(FIND_PENDING_CREATE_SQL, ROW_MAPPER, localId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void markApplied(UUID commandId, String entityId, OffsetDateTime resolvedAt) {
        jdbcTemplate.update(MARK_APPLIED_SQL, entityId, toTimestamp(resolvedAt), commandId);
    }

    @Override
    public void markFailed(UUID commandId, String error, OffsetDateTime resolvedAt) {
        jdbcTemplate.update(MARK_FAILED_SQL, error, toTimestamp(resolvedAt), commandId);
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }
}
