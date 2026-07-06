package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link SyncMappingRepository}.
 */
@Repository
class JdbcSyncMappingRepository implements SyncMappingRepository {

    private static final String INSERT_SQL = """
        INSERT INTO sync_mappings
            (id, user_id, local_id, external_system, external_id, last_known_checksum, sync_status, last_synced_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String FIND_SQL = """
        SELECT id, user_id, local_id, external_system, external_id,
               last_known_checksum, sync_status, last_synced_at
        FROM sync_mappings
        WHERE external_system = ? AND external_id = ?
        """;

    private static final String UPDATE_SQL = """
        UPDATE sync_mappings
        SET last_known_checksum = ?, sync_status = ?, last_synced_at = ?
        WHERE external_system = ? AND external_id = ?
        """;

    private static final String DELETE_SQL = """
        DELETE FROM sync_mappings
        WHERE external_system = ? AND external_id = ?
        """;

    private static final RowMapper<SyncMapping> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp syncedAt = rs.getTimestamp("last_synced_at");
        return new SyncMapping(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("local_id", UUID.class),
            rs.getString("external_system"),
            rs.getString("external_id"),
            rs.getString("last_known_checksum"),
            rs.getString("sync_status"),
            syncedAt != null ? syncedAt.toInstant().atOffset(java.time.ZoneOffset.UTC) : null);
    };

    private final JdbcTemplate jdbcTemplate;

    JdbcSyncMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SyncMapping> findByExternalSystemAndId(String externalSystem, String externalId) {
        List<SyncMapping> rows = jdbcTemplate.query(FIND_SQL, ROW_MAPPER, externalSystem, externalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void insert(SyncMapping m) {
        jdbcTemplate.update(INSERT_SQL,
            m.id(), m.userId(), m.localId(),
            m.externalSystem(), m.externalId(),
            m.lastKnownChecksum(), m.syncStatus(),
            toTimestamp(m.lastSyncedAt()));
    }

    @Override
    public void update(SyncMapping m) {
        jdbcTemplate.update(UPDATE_SQL,
            m.lastKnownChecksum(), m.syncStatus(),
            toTimestamp(m.lastSyncedAt()),
            m.externalSystem(), m.externalId());
    }

    @Override
    public void deleteByExternalSystemAndId(String externalSystem, String externalId) {
        jdbcTemplate.update(DELETE_SQL, externalSystem, externalId);
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }
}
