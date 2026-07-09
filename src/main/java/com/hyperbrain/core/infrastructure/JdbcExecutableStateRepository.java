package com.hyperbrain.core.infrastructure;

import com.hyperbrain.core.domain.model.FocusCandidate;
import com.hyperbrain.core.domain.model.SnapshotSubtask;
import com.hyperbrain.core.domain.model.SubtaskCounts;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link ExecutableStateRepository}. Every write targets only the
 * SYSTEM-owned accounting columns (ADR-013) or columns the sync upsert never lists
 * ({@code last_completed_at}), so these side writes and the ingestion upsert compose without
 * clobbering each other inside the same transaction.
 */
@Repository
class JdbcExecutableStateRepository implements ExecutableStateRepository {

    private static final String CANDIDATE_COLUMNS = """
        e.id, e.user_id, e.name, e.effort_score, e.is_important,
        p.energy_drain, p.mental_load, p.impact, p.estimated_minutes
        """;

    private static final String FIND_ACTIVE_FOCUS_SQL = """
        SELECT %s
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.user_id = ?
          AND e.id <> ?
          AND e.status = 'IN_PROGRESS'
          AND e.type <> 'AGENDA'
          AND e.system_generated = false
          AND EXISTS (SELECT 1 FROM core_time_block b
                      WHERE b.executable_id = e.id AND b.status = 'ACTIVE')
        """.formatted(CANDIDATE_COLUMNS);

    private static final String FIND_LEGACY_IN_PROGRESS_SQL = """
        SELECT %s
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.user_id = ?
          AND e.id <> ?
          AND e.status = 'IN_PROGRESS'
          AND e.type <> 'AGENDA'
          AND e.system_generated = false
          AND e.pending_reestimation = false
          AND NOT EXISTS (SELECT 1 FROM core_time_block b WHERE b.executable_id = e.id)
        """.formatted(CANDIDATE_COLUMNS);

    private static final String IS_SYSTEM_GENERATED_SQL =
        "SELECT system_generated FROM core_executable WHERE id = ?";

    private static final String COUNT_USER_SUBTASKS_SQL = """
        SELECT COUNT(*) AS total,
               COUNT(*) FILTER (WHERE status = 'DONE') AS done
        FROM core_executable
        WHERE parent_id = ? AND system_generated = false AND id <> ?
        """;

    private static final String INSERT_SNAPSHOT_SQL = """
        INSERT INTO core_executable
            (id, user_id, parent_id, name, description, type, status,
             effort_score, is_important, system_generated, start_time, last_completed_at)
        VALUES (?, ?, ?, ?, ?, 'TASK', 'DONE', ?, ?, true, ?, ?)
        """;

    private static final String INSERT_SNAPSHOT_PROFILE_SQL = """
        INSERT INTO core_execution_profile
            (executable_id, estimated_minutes, energy_drain, mental_load, impact)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String FLAG_PENDING_REESTIMATION_SQL = """
        UPDATE core_executable
        SET pending_reestimation = true
        WHERE id = ?
        """;

    private static final String CLEAR_PENDING_SQL = """
        UPDATE core_executable
        SET pending_reestimation = false
        WHERE id = ? AND pending_reestimation = true
        """;

    private static final String UPDATE_PROGRESS_SQL =
        "UPDATE core_executable SET progress = ? WHERE id = ?";

    private static final String MARK_COMPLETED_SQL =
        "UPDATE core_executable SET last_completed_at = ? WHERE id = ?";

    private static final String IMPUTE_TO_BLOCK_SQL =
        "UPDATE core_executable SET imputed_time_block_id = ? WHERE id = ?";

    private static final String CLEAR_IMPUTATION_SQL =
        "UPDATE core_executable SET imputed_time_block_id = NULL WHERE id = ?";

    private static final String IMPUTE_COMPLETED_SQL = """
        UPDATE core_executable
        SET imputed_time_block_id = ?
        WHERE parent_id = ?
          AND status = 'DONE'
          AND system_generated = false
          AND imputed_time_block_id IS NULL
          AND last_completed_at BETWEEN ? AND ?
        """;

    private static final RowMapper<FocusCandidate> CANDIDATE_MAPPER = (rs, rowNum) ->
        new FocusCandidate(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("name"),
            rs.getObject("effort_score", Double.class),
            rs.getObject("is_important", Boolean.class),
            rs.getObject("energy_drain", Integer.class),
            rs.getObject("mental_load", Integer.class),
            rs.getObject("impact", Integer.class),
            rs.getObject("estimated_minutes", Integer.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcExecutableStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<FocusCandidate> findActiveFocus(UUID userId, UUID excludingId) {
        return jdbcTemplate.query(FIND_ACTIVE_FOCUS_SQL, CANDIDATE_MAPPER, userId, excludingId);
    }

    @Override
    public List<FocusCandidate> findLegacyInProgress(UUID userId, UUID excludingId) {
        return jdbcTemplate.query(FIND_LEGACY_IN_PROGRESS_SQL, CANDIDATE_MAPPER, userId, excludingId);
    }

    @Override
    public boolean isSystemGenerated(UUID executableId) {
        List<Boolean> rows = jdbcTemplate.query(IS_SYSTEM_GENERATED_SQL,
            (rs, rowNum) -> rs.getBoolean("system_generated"), executableId);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    @Override
    public SubtaskCounts countUserSubtasks(UUID parentId, UUID excludingId) {
        return jdbcTemplate.queryForObject(COUNT_USER_SUBTASKS_SQL,
            (rs, rowNum) -> new SubtaskCounts(rs.getInt("total"), rs.getInt("done")),
            parentId, excludingId);
    }

    @Override
    public void insertSystemSnapshot(SnapshotSubtask s) {
        jdbcTemplate.update(INSERT_SNAPSHOT_SQL,
            s.id(), s.userId(), s.parentId(), s.name(), s.description(),
            s.effortScore(), Boolean.TRUE.equals(s.isImportant()),
            toTimestamp(s.windowStart()), toTimestamp(s.completedAt()));
        jdbcTemplate.update(INSERT_SNAPSHOT_PROFILE_SQL,
            s.id(), s.estimatedMinutes(), s.energyDrain(), s.mentalLoad(), s.impact());
    }

    @Override
    public void flagPendingReestimation(UUID executableId) {
        jdbcTemplate.update(FLAG_PENDING_REESTIMATION_SQL, executableId);
    }

    @Override
    public boolean clearPendingReestimation(UUID executableId) {
        return jdbcTemplate.update(CLEAR_PENDING_SQL, executableId) > 0;
    }

    @Override
    public void updateProgress(UUID executableId, Double progress) {
        jdbcTemplate.update(UPDATE_PROGRESS_SQL, progress, executableId);
    }

    @Override
    public void markCompleted(UUID executableId, OffsetDateTime completedAt) {
        jdbcTemplate.update(MARK_COMPLETED_SQL, toTimestamp(completedAt), executableId);
    }

    @Override
    public void imputeToBlock(UUID subtaskId, UUID blockId) {
        jdbcTemplate.update(IMPUTE_TO_BLOCK_SQL, blockId, subtaskId);
    }

    @Override
    public void clearImputation(UUID subtaskId) {
        jdbcTemplate.update(CLEAR_IMPUTATION_SQL, subtaskId);
    }

    @Override
    public int imputeCompletedSubtasks(UUID blockId, UUID executableId,
                                       OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        return jdbcTemplate.update(IMPUTE_COMPLETED_SQL,
            blockId, executableId, toTimestamp(windowStart), toTimestamp(windowEnd));
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }
}
