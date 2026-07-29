package com.hyperbrain.sync;

import com.hyperbrain.sync.domain.port.out.CoreCycleAreaRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@code core_cycle_area} bridge (ADR-036) against a real PostgreSQL: the full-mirror
 * replace, the {@code ON DELETE CASCADE} FK, and the DDL guarantees the Flyway mirror must carry — the
 * {@code AREA} type is accepted, an {@code AREA} cannot bear an {@code end_date} (perpetuity CHECK), and
 * a self-membership is rejected. Black-box: only the public {@link CoreCycleAreaRepository} plus raw SQL
 * for the constraints.
 */
@IntegrationTest
@DisplayName("core_cycle_area — M:N bridge persistence + DDL constraints (ADR-036)")
class JdbcCoreCycleAreaRepositoryIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CoreCycleAreaRepository repository;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM core_cycle_area");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("replaceMembershipsForCycle is a full mirror: it persists, reads back and clears the set")
    void full_mirror_replace() {
        UUID project = insertCycle("PROJECT", null);
        UUID family = insertArea("Family");
        UUID money = insertArea("Money");

        repository.replaceMembershipsForCycle(project, Set.of(family, money));
        assertThat(repository.findAreaIdsByCycle(project)).containsExactlyInAnyOrder(family, money);

        // Replacing with a smaller set drops the removed membership (full mirror).
        repository.replaceMembershipsForCycle(project, Set.of(family));
        assertThat(repository.findAreaIdsByCycle(project)).containsExactly(family);

        // Replacing with the empty set clears every membership.
        repository.replaceMembershipsForCycle(project, Set.of());
        assertThat(repository.findAreaIdsByCycle(project)).isEmpty();
    }

    @Test
    @DisplayName("re-applying the same set is idempotent (no duplicate rows)")
    void replace_is_idempotent() {
        UUID project = insertCycle("PROJECT", null);
        UUID family = insertArea("Family");

        repository.replaceMembershipsForCycle(project, Set.of(family));
        repository.replaceMembershipsForCycle(project, Set.of(family));

        Integer rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_cycle_area WHERE cycle_id = ?", Integer.class, project);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("ON DELETE CASCADE: deleting either endpoint cycle removes the membership")
    void fk_cascade_removes_membership() {
        UUID project = insertCycle("PROJECT", null);
        UUID family = insertArea("Family");
        repository.replaceMembershipsForCycle(project, Set.of(family));

        jdbcTemplate.update("DELETE FROM core_cycle WHERE id = ?", family);

        assertThat(repository.findAreaIdsByCycle(project)).isEmpty();
    }

    @Test
    @DisplayName("the type CHECK accepts AREA (Flyway mirror of the Infra DDL)")
    void type_check_accepts_area() {
        UUID area = insertArea("Health");
        String type = jdbcTemplate.queryForObject(
            "SELECT type FROM core_cycle WHERE id = ?", String.class, area);
        assertThat(type).isEqualTo("AREA");
    }

    @Test
    @DisplayName("the perpetuity CHECK rejects an AREA that carries an end_date")
    void perpetuity_check_rejects_dated_area() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, end_date)
            VALUES (?, ?, 'Bad area', 'AREA', 'ACTIVE', ?)
            """, UUID.randomUUID(), USER, LocalDate.now().plusDays(30)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the no-self CHECK rejects a self-membership")
    void no_self_check_rejects_self_membership() {
        UUID area = insertArea("Family");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO core_cycle_area (cycle_id, area_id) VALUES (?, ?)", area, area))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertArea(String name) {
        return insertNamedCycle(name, "AREA", null);
    }

    private UUID insertCycle(String type, LocalDate endDate) {
        return insertNamedCycle(type + "-cycle", type, endDate);
    }

    private UUID insertNamedCycle(String name, String type, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, end_date)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?)
            """, id, USER, name, type, endDate);
        return id;
    }
}
