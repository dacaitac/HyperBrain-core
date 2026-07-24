package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.NormalizationStatus;
import com.hyperbrain.planner.domain.model.RawTelemetryRow;
import com.hyperbrain.planner.domain.port.out.RawTelemetryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link RawTelemetryStore} ({@code context_event}, ADR-016 raw-first).
 *
 * <p>The insert uses {@code ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING} — the
 * predicate matches the partial unique index {@code uq_context_event_dedup_key}, so a semantic
 * duplicate is a no-op that leaves the transaction usable (as opposed to catching a unique violation,
 * which would poison the PostgreSQL transaction).
 */
@Repository
class JdbcRawTelemetryStore implements RawTelemetryStore {

    private static final String INSERT_SQL = """
        INSERT INTO context_event
            (id, user_id, source, provider, event_type, payload, occurred_at,
             dedup_key, schema_version, ingested_at, normalization_status)
        VALUES (?, ?, 'INTEGRATION', ?, ?, ?::jsonb, ?, ?, ?, now(), 'PENDING')
        ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING
        """;

    private static final String MARK_STATUS_SQL =
        "UPDATE context_event SET normalization_status = ? WHERE id = ?";

    private static final String PURGE_SQL = """
        DELETE FROM context_event
        WHERE normalization_status IN ('NORMALIZED', 'SKIPPED')
          AND ingested_at < now() - make_interval(days => ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcRawTelemetryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> insertPending(RawTelemetryRow row) {
        UUID id = UUID.randomUUID();
        int inserted = jdbcTemplate.update(INSERT_SQL,
            id, row.userId(), row.provider(), row.eventType(), row.payloadJson(),
            row.occurredAt(), row.dedupKey(), row.schemaVersion());
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    @Override
    public void markStatus(UUID id, NormalizationStatus status) {
        jdbcTemplate.update(MARK_STATUS_SQL, status.name(), id);
    }

    @Override
    public int purgeProcessedOlderThan(int retentionDays) {
        return jdbcTemplate.update(PURGE_SQL, retentionDays);
    }
}
