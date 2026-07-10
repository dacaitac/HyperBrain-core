package com.hyperbrain.prioritizer;

import com.hyperbrain.prioritizer.application.PriorityReflectionService;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the #66a scheduled tick (a) against a real PostgreSQL: {@code reflectDailyReprioritization}
 * scores the day and stages an outbox reflection only for the executables whose score actually moved —
 * never one per row. Black-box: only the public {@link PriorityReflectionService} and the persisted
 * outbox are exercised.
 */
@IntegrationTest
@DisplayName("PriorityReflectionService — daily tick stages outbox only for changed scores (#66a)")
class PriorityReflectionIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PriorityReflectionService reflectionService;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("first tick scores every pending row and stages one outbox reflection per row")
    void first_tick_reflects_all_pending() {
        // Impact-only factors (no due date) keep the score deterministic across ticks.
        UUID a = insertTask("A", 5);
        UUID b = insertTask("B", 1);

        int reflected = reflectionService.reflectDailyReprioritization(USER);

        assertThat(reflected).isEqualTo(2);
        assertThat(outboxLocalIds()).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(outboxEventTypes()).containsOnly("ExecutableUpdatedEvent");
        assertThat(outboxSources()).containsOnly("SYSTEM");
        assertThat(priorityOf(a)).isNotNull();
    }

    @Test
    @DisplayName("a second identical tick is idempotent: no score moves, so no new outbox event is staged")
    void second_identical_tick_stages_nothing() {
        // No due date -> urgency is a constant 0, so the score is stable across ticks.
        insertTask("A", 5);
        insertTask("B", 1);
        reflectionService.reflectDailyReprioritization(USER);
        jdbcTemplate.update("DELETE FROM outbox_events");

        int reflected = reflectionService.reflectDailyReprioritization(USER);

        assertThat(reflected).isZero();
        assertThat(outboxLocalIds()).isEmpty();
    }

    @Test
    @DisplayName("only the row whose factors changed is reflected, not the whole day")
    void only_changed_row_is_reflected() {
        UUID stable = insertTask("stable", 1);      // P = 0, will not move on re-run
        UUID moving = insertTask("moving", 3);      // impact 3 -> 0.5*0.4 = 0.2
        reflectionService.reflectDailyReprioritization(USER);
        jdbcTemplate.update("DELETE FROM outbox_events");

        // Raise the moving row's impact so its score changes on the next tick; stable stays put
        jdbcTemplate.update(
            "UPDATE core_execution_profile SET impact = 5 WHERE executable_id = ?", moving);

        int reflected = reflectionService.reflectDailyReprioritization(USER);

        assertThat(reflected).isEqualTo(1);
        assertThat(outboxLocalIds()).containsExactly(moving.toString());
        assertThat(outboxLocalIds()).doesNotContain(stable.toString());
    }

    private UUID insertTask(String name, int impact) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, effort_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', 2.0)
            """, id, USER, name);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, impact) VALUES (?, ?)
            """, id, impact);
        return id;
    }

    private List<String> outboxLocalIds() {
        return jdbcTemplate.queryForList(
            "SELECT aggregate_id FROM outbox_events WHERE aggregate_type = 'CORE_EXECUTABLE'",
            String.class);
    }

    private List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox_events", String.class);
    }

    private List<String> outboxSources() {
        return jdbcTemplate.queryForList("SELECT source_system FROM outbox_events", String.class);
    }

    private Double priorityOf(UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT priority_score FROM core_executable WHERE id = ?", Double.class, id);
    }
}
