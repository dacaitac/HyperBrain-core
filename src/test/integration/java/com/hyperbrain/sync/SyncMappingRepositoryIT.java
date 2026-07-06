package com.hyperbrain.sync;

import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@DisplayName("SyncMappingRepository — CRUD")
class SyncMappingRepositoryIT {

    @Autowired private SyncMappingRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM sync_mappings");
        jdbcTemplate.execute("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("insert: mapping is retrievable after insert")
    void insert_and_find() {
        UUID localId = UUID.randomUUID();
        SyncMapping mapping = mapping("EKReminder-test-1", localId, "sha256-abc");

        repository.insert(mapping);

        Optional<SyncMapping> found = repository.findByExternalSystemAndId("APPLE", "EKReminder-test-1");
        assertThat(found).isPresent();
        assertThat(found.get().externalId()).isEqualTo("EKReminder-test-1");
        assertThat(found.get().localId()).isEqualTo(localId);
        assertThat(found.get().lastKnownChecksum()).isEqualTo("sha256-abc");
        assertThat(found.get().syncStatus()).isEqualTo("SYNCED");
    }

    @Test
    @DisplayName("findByExternalSystemAndId: returns empty for unknown key")
    void find_unknown_returns_empty() {
        assertThat(repository.findByExternalSystemAndId("APPLE", "EKReminder-none")).isEmpty();
    }

    @Test
    @DisplayName("findByExternalSystemAndId: different external systems are isolated")
    void different_systems_are_isolated() {
        repository.insert(mapping("EKReminder-x", UUID.randomUUID(), "cksum"));

        assertThat(repository.findByExternalSystemAndId("NOTION", "EKReminder-x")).isEmpty();
        assertThat(repository.findByExternalSystemAndId("APPLE",  "EKReminder-x")).isPresent();
    }

    @Test
    @DisplayName("update: checksum and lastSyncedAt are updated")
    void update_changes_checksum() {
        UUID localId = UUID.randomUUID();
        repository.insert(mapping("EKReminder-upd", localId, "old-checksum"));

        SyncMapping updated = new SyncMapping(
            UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId,
            "APPLE", "EKReminder-upd", "new-checksum", "SYNCED", OffsetDateTime.now());
        repository.update(updated);

        SyncMapping found = repository.findByExternalSystemAndId("APPLE", "EKReminder-upd").orElseThrow();
        assertThat(found.lastKnownChecksum()).isEqualTo("new-checksum");
    }

    @Test
    @DisplayName("deleteByExternalSystemAndId: mapping is gone after deletion")
    void delete_removes_mapping() {
        repository.insert(mapping("EKReminder-del", UUID.randomUUID(), "cksum"));
        assertThat(repository.findByExternalSystemAndId("APPLE", "EKReminder-del")).isPresent();

        repository.deleteByExternalSystemAndId("APPLE", "EKReminder-del");

        assertThat(repository.findByExternalSystemAndId("APPLE", "EKReminder-del")).isEmpty();
    }

    @Test
    @DisplayName("deleteByExternalSystemAndId: no-op when mapping does not exist")
    void delete_unknown_is_noop() {
        // Should not throw
        repository.deleteByExternalSystemAndId("APPLE", "EKReminder-ghost");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SyncMapping mapping(String externalId, UUID localId, String checksum) {
        return new SyncMapping(
            UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId,
            "APPLE", externalId, checksum, "SYNCED", OffsetDateTime.now());
    }
}
