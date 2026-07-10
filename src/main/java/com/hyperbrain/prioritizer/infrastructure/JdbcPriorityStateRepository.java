package com.hyperbrain.prioritizer.infrastructure;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext.AncestorLink;
import com.hyperbrain.prioritizer.domain.model.CycleType;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.prioritizer.domain.port.out.PriorityStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC adapter for {@link PriorityStateRepository}. Reads aggregate state directly
 * ({@code core_executable} joined with {@code core_execution_profile}, and the {@code core_cycle}
 * hierarchy for graded alignment) and writes back only {@code core_executable.priority_score}.
 */
@Repository
class JdbcPriorityStateRepository implements PriorityStateRepository {

    /**
     * The fixed deadline horizon {@code H} (days) the raw urgency ramp is anchored to: urgency starts
     * rising only once the due date is within {@code H} days of now (Daniel, Comité 2026-07-09). A
     * calibrable domain constant — replacing the old creation-anchored ramp that pinned every old task
     * at maximum urgency permanently.
     */
    private static final int URGENCY_HORIZON_DAYS = 7;

    /**
     * The day's schedulable executables with their raw factors. Urgency is derived here onto the
     * 0–6 source scale, re-anchored to a fixed deadline horizon {@code H} (days):
     * {@code days_to_deadline = (end_time − now)} in days, and
     * {@code urgency = 5 · clamp(1 − days_to_deadline / H, 0, 1)} — {@code 0} while the deadline is more
     * than {@code H} days away, ramping to {@code 5} at the deadline, and rising above {@code 5} up to
     * the cap {@code 6} once overdue. Executables with no due date carry no urgency signal ({@code 0}).
     * SYSTEM-generated accounting rows and read-only AGENDA blocks are excluded — they are not the
     * user's actionable work.
     */
    private static final String FIND_TODAYS_FACTORS_SQL = """
        SELECT e.id,
               e.cycle_id,
               p.impact,
               e.effort_score,
               CASE
                   WHEN e.end_time IS NULL THEN 0
                   ELSE LEAST(
                       6,
                       5 * GREATEST(
                           0,
                           1 - (EXTRACT(EPOCH FROM (e.end_time - now())) / 86400.0)
                               / %d
                       )
                   )
               END AS urgency_raw
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.user_id = ?
          AND e.status IN ('TODO', 'IN_PROGRESS')
          AND e.type <> 'AGENDA'
          AND e.system_generated = false
        """.formatted(URGENCY_HORIZON_DAYS);

    /**
     * The graded-alignment context per cycle for one user, computed in a single recursive pass. From
     * every cycle the CTE walks {@code parent_cycle_id} upward, carrying the source cycle, the current
     * depth and the visited path; each hop that lands on an {@code ACTIVE} ancestor is an alignment
     * link. The outer query keeps the minimum distance per {@code (source, ancestor type)} pair (a
     * closer ancestor of the same band always dominates via {@code δ}), and joins the source cycle's
     * own type so the domain can apply the Coach cap.
     *
     * <p>The free-form parent graph (ADR-015) may contain cycles: {@code cycle_guard} carries the
     * visited path and a hop only recurses when the next parent is not already on it and the depth
     * stays under the defensive bound (16). {@code UNION ALL} + the explicit guard replace the
     * distinct-based cycle detection so distances are preserved.
     */
    private static final String FIND_ALIGNMENT_CONTEXTS_SQL = """
        WITH RECURSIVE walk (source_id, current_id, depth, path) AS (
            SELECT c.id, c.id, 0, ARRAY[c.id]
            FROM core_cycle c
            WHERE c.user_id = ?
            UNION ALL
            SELECT w.source_id, parent.id, w.depth + 1, w.path || parent.id
            FROM walk w
            JOIN core_cycle child ON child.id = w.current_id
            JOIN core_cycle parent ON parent.id = child.parent_cycle_id
            WHERE w.depth < 16
              AND NOT parent.id = ANY(w.path)
        ),
        ancestor_link AS (
            SELECT w.source_id,
                   anc.type AS ancestor_type,
                   MIN(w.depth) AS distance
            FROM walk w
            JOIN core_cycle anc ON anc.id = w.current_id
            WHERE anc.status = 'ACTIVE'
            GROUP BY w.source_id, anc.type
        )
        SELECT src.id AS source_id,
               src.type AS own_type,
               al.ancestor_type,
               al.distance
        FROM core_cycle src
        LEFT JOIN ancestor_link al ON al.source_id = src.id
        WHERE src.user_id = ?
        """;

    private static final String SAVE_SCORE_SQL =
        "UPDATE core_executable SET priority_score = ? WHERE id = ?";

    private static final RowMapper<ExecutableFactors> FACTORS_MAPPER = (rs, rowNum) ->
        new ExecutableFactors(
            rs.getObject("id", UUID.class),
            rs.getObject("cycle_id", UUID.class),
            rs.getObject("impact", Integer.class),
            rs.getDouble("urgency_raw"),
            rs.getObject("effort_score", Double.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcPriorityStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ExecutableFactors> findTodaysFactors(UUID userId) {
        return jdbcTemplate.query(FIND_TODAYS_FACTORS_SQL, FACTORS_MAPPER, userId);
    }

    @Override
    public Map<UUID, CycleAlignmentContext> findAlignmentContexts(UUID userId) {
        Map<UUID, CycleType> ownTypes = new HashMap<>();
        Map<UUID, List<AncestorLink>> links = new HashMap<>();

        jdbcTemplate.query(FIND_ALIGNMENT_CONTEXTS_SQL, rs -> {
            UUID sourceId = rs.getObject("source_id", UUID.class);
            ownTypes.putIfAbsent(sourceId, CycleType.valueOf(rs.getString("own_type")));
            links.putIfAbsent(sourceId, new ArrayList<>());

            String ancestorType = rs.getString("ancestor_type");
            if (ancestorType != null) {
                links.get(sourceId).add(
                    new AncestorLink(CycleType.valueOf(ancestorType), rs.getInt("distance")));
            }
        }, userId, userId);

        Map<UUID, CycleAlignmentContext> contexts = new HashMap<>();
        ownTypes.forEach((sourceId, ownType) ->
            contexts.put(sourceId, new CycleAlignmentContext(ownType, links.get(sourceId))));
        return contexts;
    }

    @Override
    public void saveScores(List<PriorityScore> scores) {
        if (scores.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(SAVE_SCORE_SQL, scores, scores.size(),
            (ps, score) -> {
                ps.setDouble(1, score.score());
                ps.setObject(2, score.executableId());
            });
    }
}
