package com.hyperbrain.sync;

import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@DisplayName("CoreExecutableRepository — CRUD")
class CoreExecutableRepositoryIT {

    @Autowired private CoreExecutableRepository repository;
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
    @DisplayName("insert: row is retrievable after insert")
    void insert_and_find() {
        CoreExecutable exe = task("Buy milk", "TODO", null, null, "HyperBrain");

        repository.insert(exe);

        Optional<CoreExecutable> found = repository.findById(exe.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Buy milk");
        assertThat(found.get().type()).isEqualTo("TASK");
        assertThat(found.get().status()).isEqualTo("TODO");
        assertThat(found.get().sourceCalendar()).isEqualTo("HyperBrain");
    }

    @Test
    @DisplayName("insert: startTime and endTime are persisted and retrieved")
    void insert_preserves_times() {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-07T09:00:00-05:00");
        OffsetDateTime end   = OffsetDateTime.parse("2026-07-07T10:00:00-05:00");
        CoreExecutable exe = activity("Team meeting", start, end, "Work");

        repository.insert(exe);

        CoreExecutable found = repository.findById(exe.id()).orElseThrow();
        assertThat(found.startTime()).isNotNull();
        assertThat(found.endTime()).isNotNull();
        // Compare as instants to be timezone-offset-agnostic
        assertThat(found.startTime().toInstant()).isEqualTo(start.toInstant());
        assertThat(found.endTime().toInstant()).isEqualTo(end.toInstant());
    }

    @Test
    @DisplayName("findById: returns empty for unknown id")
    void find_by_unknown_id_is_empty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("update: name, status and sourceCalendar are updated")
    void update_mutates_fields() {
        CoreExecutable original = task("Old title", "TODO", null, null, "List A");
        repository.insert(original);

        CoreExecutable updated = new CoreExecutable(
            original.id(), original.userId(), "New title", "updated notes", original.type(), "DONE",
            null, null, "List B");
        repository.update(updated);

        CoreExecutable found = repository.findById(original.id()).orElseThrow();
        assertThat(found.name()).isEqualTo("New title");
        assertThat(found.status()).isEqualTo("DONE");
        assertThat(found.sourceCalendar()).isEqualTo("List B");
    }

    @Test
    @DisplayName("update: start_time and end_time can be changed")
    void update_changes_times() {
        OffsetDateTime start = OffsetDateTime.parse("2026-07-07T09:00:00-05:00");
        CoreExecutable original = activity("Meeting", start, null, "Work");
        repository.insert(original);

        OffsetDateTime newEnd = OffsetDateTime.parse("2026-07-07T11:00:00-05:00");
        CoreExecutable updated = new CoreExecutable(
            original.id(), original.userId(), original.name(), original.description(), original.type(),
            original.status(), original.startTime(), newEnd, original.sourceCalendar());
        repository.update(updated);

        CoreExecutable found = repository.findById(original.id()).orElseThrow();
        assertThat(found.endTime().toInstant()).isEqualTo(newEnd.toInstant());
    }

    @Test
    @DisplayName("deleteById: row is gone after deletion")
    void delete_removes_row() {
        CoreExecutable exe = task("Temp task", "TODO", null, null, null);
        repository.insert(exe);
        assertThat(repository.findById(exe.id())).isPresent();

        repository.deleteById(exe.id());

        assertThat(repository.findById(exe.id())).isEmpty();
    }

    @Test
    @DisplayName("deleteById: no-op when id does not exist")
    void delete_unknown_id_is_noop() {
        // Should not throw
        repository.deleteById(UUID.randomUUID());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static CoreExecutable task(String name, String status,
                                       OffsetDateTime startTime, OffsetDateTime endTime,
                                       String sourceCalendar) {
        return new CoreExecutable(UUID.randomUUID(), DataFixture.SYSTEM_USER_ID,
            name, "some notes", "TASK", status, startTime, endTime, sourceCalendar);
    }

    private static CoreExecutable activity(String name, OffsetDateTime startTime,
                                            OffsetDateTime endTime, String calendar) {
        return new CoreExecutable(UUID.randomUUID(), DataFixture.SYSTEM_USER_ID,
            name, null, "ACTIVITY", "TODO", startTime, endTime, calendar);
    }
}
