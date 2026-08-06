package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.SettledObservation;
import com.hyperbrain.planner.domain.model.TaskCostInputs;
import com.hyperbrain.planner.domain.port.out.LearnedCostRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link LearnedCostRepository} (spike #63). Reads only settled-block history and
 * the task's estimate — no writes.
 *
 * <p>The imputed-subtask count is derived by counting {@code core_executable} rows imputed to each
 * block; only real user subtasks count ({@code system_generated = false} excludes the focus-cut
 * snapshots, DR-06). The observations read the union of the two block models (ADR-039): the frozen
 * legacy {@code core_time_block} history (imputed via {@code imputed_time_block_id}) and the new
 * {@code TIME_BLOCK} executables that account for the task (imputed via {@code imputed_block_id}).
 * Terminal blocks with a non-null {@code actual_duration_minutes} are kept (the expiry sweep settles
 * never-executed blocks with a null actual on purpose), oldest settlement first so the EWMA folds
 * them chronologically.
 */
@Repository
class JdbcLearnedCostRepository implements LearnedCostRepository {

    private static final String OBSERVATIONS_SQL = """
        SELECT actual_minutes, imputed_count FROM (
            SELECT b.actual_duration_minutes AS actual_minutes,
                   b.settled_at              AS settled_at,
                   (SELECT COUNT(*) FROM core_executable e
                    WHERE e.imputed_time_block_id = b.id
                      AND e.system_generated = false) AS imputed_count
            FROM core_time_block b
            WHERE b.executable_id = ?
              AND b.actual_duration_minutes IS NOT NULL
              AND b.status IN ('SETTLED', 'EXPIRED')
            UNION ALL
            SELECT nb.actual_duration_minutes AS actual_minutes,
                   nb.last_completed_at       AS settled_at,
                   (SELECT COUNT(*) FROM core_executable e
                    WHERE e.imputed_block_id = nb.id
                      AND e.system_generated = false) AS imputed_count
            FROM core_executable nb
            WHERE nb.type = 'TIME_BLOCK'
              AND nb.parent_id = ?
              AND nb.actual_duration_minutes IS NOT NULL
              AND nb.status IN ('DONE', 'FAILED')
        ) observations
        ORDER BY settled_at
        """;

    private static final String ESTIMATE_SQL = """
        SELECT estimated_minutes
        FROM core_execution_profile
        WHERE executable_id = ?
        """;

    private static final String TOTAL_SUBTASKS_SQL = """
        SELECT COUNT(*)
        FROM core_executable
        WHERE parent_id = ? AND system_generated = false
        """;

    private static final RowMapper<SettledObservation> OBSERVATION_MAPPER = (rs, rowNum) ->
        new SettledObservation(rs.getInt("actual_minutes"), rs.getInt("imputed_count"));

    private final JdbcTemplate jdbcTemplate;

    JdbcLearnedCostRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TaskCostInputs loadCostInputs(UUID taskId) {
        List<SettledObservation> observations =
            jdbcTemplate.query(OBSERVATIONS_SQL, OBSERVATION_MAPPER, taskId, taskId);
        Integer estimatedMinutes = jdbcTemplate.query(ESTIMATE_SQL,
            (rs, rowNum) -> rs.getObject("estimated_minutes", Integer.class), taskId)
            .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Integer totalSubtasks =
            jdbcTemplate.queryForObject(TOTAL_SUBTASKS_SQL, Integer.class, taskId);

        return new TaskCostInputs(taskId, observations, estimatedMinutes,
            totalSubtasks == null ? 0 : totalSubtasks);
    }
}
