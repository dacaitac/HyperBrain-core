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
 * <p>Both landing statements carry {@code ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL} — the
 * predicate matches the partial unique index {@code uq_context_event_dedup_key} — so a semantic
 * duplicate resolves inside the statement and leaves the transaction usable, as opposed to catching a
 * unique violation, which would poison the PostgreSQL transaction. They differ only in what a conflict
 * means: {@code DO NOTHING} for an immutable fact re-delivered, {@code DO UPDATE} for one the provider
 * keeps revising.
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

    /**
     * The re-sendable counterpart of {@link #INSERT_SQL}: same partial-index predicate, but the
     * conflict overwrites the stored version instead of discarding the new one. {@code RETURNING id}
     * yields the surviving row's id on both branches — the generated one on insert, the pre-existing one
     * on overwrite — which is what lets the typed record keep pointing at its raw origin across re-sends.
     */
    private static final String UPSERT_SQL = """
        INSERT INTO context_event
            (id, user_id, source, provider, event_type, payload, occurred_at,
             dedup_key, schema_version, ingested_at, normalization_status)
        VALUES (?, ?, 'INTEGRATION', ?, ?, ?::jsonb, ?, ?, ?, now(), 'PENDING')
        ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL DO UPDATE
           SET payload              = EXCLUDED.payload,
               occurred_at          = EXCLUDED.occurred_at,
               provider             = EXCLUDED.provider,
               event_type           = EXCLUDED.event_type,
               schema_version       = EXCLUDED.schema_version,
               ingested_at          = EXCLUDED.ingested_at,
               normalization_status = 'PENDING'
        RETURNING id
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
    public UUID upsertLatest(RawTelemetryRow row) {
        return jdbcTemplate.queryForObject(UPSERT_SQL, UUID.class,
            UUID.randomUUID(), row.userId(), row.provider(), row.eventType(), row.payloadJson(),
            row.occurredAt(), row.dedupKey(), row.schemaVersion());
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
