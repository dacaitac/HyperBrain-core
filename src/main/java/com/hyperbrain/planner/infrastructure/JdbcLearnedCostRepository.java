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
 * <p>The imputed-subtask count is not a column of {@code core_time_block}; it is derived by joining
 * {@code core_executable} on {@code imputed_time_block_id} (index
 * {@code idx_core_executable_imputed_block}) and counting the rows per block. Only real user subtasks
 * count: {@code system_generated = false} excludes the focus-cut snapshots (DR-06), which would
 * otherwise inflate the divisor and bias {@code cu} low. The observation query keeps {@code SETTLED}
 * blocks and {@code EXPIRED} blocks whose {@code actual_duration_minutes} is non-null (the expiry
 * scheduler settles never-executed blocks with a null actual on purpose), ordered by
 * {@code settled_at} so the EWMA folds them oldest-first.
 */
@Repository
class JdbcLearnedCostRepository implements LearnedCostRepository {

    private static final String OBSERVATIONS_SQL = """
        SELECT b.actual_duration_minutes AS actual_minutes,
               (SELECT COUNT(*) FROM core_executable e
                WHERE e.imputed_time_block_id = b.id
                  AND e.system_generated = false) AS imputed_count
        FROM core_time_block b
        WHERE b.executable_id = ?
          AND b.actual_duration_minutes IS NOT NULL
          AND b.status IN ('SETTLED', 'EXPIRED')
        ORDER BY b.settled_at
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
            jdbcTemplate.query(OBSERVATIONS_SQL, OBSERVATION_MAPPER, taskId);
        Integer estimatedMinutes = jdbcTemplate.query(ESTIMATE_SQL,
            (rs, rowNum) -> rs.getObject("estimated_minutes", Integer.class), taskId)
            .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Integer totalSubtasks =
            jdbcTemplate.queryForObject(TOTAL_SUBTASKS_SQL, Integer.class, taskId);

        return new TaskCostInputs(taskId, observations, estimatedMinutes,
            totalSubtasks == null ? 0 : totalSubtasks);
    }
}
