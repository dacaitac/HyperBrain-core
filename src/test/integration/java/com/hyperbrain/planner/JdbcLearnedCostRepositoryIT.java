package com.hyperbrain.planner;

import com.hyperbrain.planner.domain.model.SettledObservation;
import com.hyperbrain.planner.domain.model.TaskCostInputs;
import com.hyperbrain.planner.domain.port.out.LearnedCostRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JDBC read side of the Planner's learned unit cost against a real PostgreSQL, focused
 * on the {@code cu} divisor fix (#63, Comité 2026-07-09): the imputed-subtask count that divides a
 * block's actual minutes must exclude focus-cut snapshots ({@code system_generated = true}), which
 * would otherwise inflate the count and bias {@code cu} low.
 */
@IntegrationTest
@DisplayName("JdbcLearnedCostRepository — cu divisor excludes system_generated snapshots (#63)")
class JdbcLearnedCostRepositoryIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LearnedCostRepository repository;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("a block with 2 user subtasks + 1 focus snapshot imputed divides by 2, not 3")
    void imputed_count_excludes_focus_snapshots() {
        UUID task = insertTask("Task", false, (UUID) null);
        UUID blockId = insertSettledBlock(task, 90);

        // Two real user subtasks imputed to the block.
        impute(insertTask("Sub 1", false, task), blockId);
        impute(insertTask("Sub 2", false, task), blockId);
        // One focus-cut snapshot imputed to the same block: must NOT count in the divisor.
        impute(insertTask("[focus] snapshot", true, task), blockId);

        TaskCostInputs inputs = repository.loadCostInputs(task);

        assertThat(inputs.observations()).hasSize(1);
        SettledObservation observation = inputs.observations().get(0);
        // 3 rows are imputed, but only the 2 user subtasks count -> 90 / 2 = 45, not 90 / 3 = 30.
        assertThat(observation.imputedSubtaskCount()).isEqualTo(2);
        assertThat(observation.unitCost()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("total subtasks for the cold-start prior also excludes snapshots")
    void total_subtasks_excludes_snapshots() {
        UUID task = insertEstimatedTask("Task", 100);
        insertTask("Sub 1", false, task);
        insertTask("Sub 2", false, task);
        insertTask("[focus] snapshot", true, task);

        TaskCostInputs inputs = repository.loadCostInputs(task);

        assertThat(inputs.totalSubtasks()).isEqualTo(2);
    }

    private UUID insertTask(String name, boolean systemGenerated, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, parent_id, name, type, status, system_generated)
            VALUES (?, ?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, parentId, name, systemGenerated);
        return id;
    }

    /** A parent task carrying an estimate, used by the cold-start prior test. */
    private UUID insertEstimatedTask(String name, int estimatedMinutes) {
        UUID id = insertTask(name, false, (UUID) null);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
    }

    private UUID insertSettledBlock(UUID executableId, int actualMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_time_block
                (id, executable_id, date_start, date_end, actual_duration_minutes, status, origin, settled_at)
            VALUES (?, ?, ?, ?, ?, 'SETTLED', 'PLANNER', ?)
            """, id, executableId, OffsetDateTime.now().minusHours(2),
            OffsetDateTime.now().minusHours(1), actualMinutes, OffsetDateTime.now().minusHours(1));
        return id;
    }

    private void impute(UUID executableId, UUID blockId) {
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = ? WHERE id = ?",
            blockId, executableId);
    }
}
