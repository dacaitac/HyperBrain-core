package com.hyperbrain.shared.outbox.infrastructure;

import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link OutboxRepository}. Uses {@code FOR UPDATE SKIP LOCKED} so multiple
 * relay instances can drain the outbox concurrently without contention or double-publishing.
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String LOCK_BATCH_SQL = """
        SELECT id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at
        FROM outbox_events
        WHERE processed = false
        ORDER BY occurred_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """;

    private static final String MARK_PROCESSED_SQL =
        "UPDATE outbox_events SET processed = true, processed_at = now() WHERE id = ?";

    private static final String PURGE_SQL =
        "DELETE FROM outbox_events WHERE processed = true AND processed_at < now() - make_interval(days => ?)";

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
        rs.getObject("id", UUID.class),
        rs.getString("aggregate_type"),
        rs.getString("aggregate_id"),
        rs.getString("event_type"),
        rs.getString("payload"),
        rs.getString("source_system"),
        rs.getObject("occurred_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OutboxEvent> lockUnprocessedBatch(int limit) {
        return jdbcTemplate.query(LOCK_BATCH_SQL, ROW_MAPPER, limit);
    }

    @Override
    public void markProcessed(UUID id) {
        jdbcTemplate.update(MARK_PROCESSED_SQL, id);
    }

    @Override
    public int purgeProcessedOlderThan(int days) {
        return jdbcTemplate.update(PURGE_SQL, days);
    }
}
