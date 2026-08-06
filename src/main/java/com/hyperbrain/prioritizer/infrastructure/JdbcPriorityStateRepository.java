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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC adapter for {@link PriorityStateRepository}. Reads aggregate state directly
 * ({@code core_executable} joined with {@code core_execution_profile}, and the {@code core_cycle}
 * hierarchy for graded alignment and for the urgency deadline) and writes back only the score columns
 * of {@code core_executable} ({@code priority_score}, {@code urgency_score},
 * {@code priority_computed_at}), diffed so only rows whose score moved are touched.
 *
 * <p><b>Time-zone assumption (ADR-026, pending Daniel's validation).</b> {@code core_cycle.end_date}
 * is a {@code DATE}; a commitment is not overdue until its due day has ended, so the deadline instant
 * is the start of the following day: {@code (end_date + INTERVAL '1 day')::timestamptz}. The
 * {@code ::timestamptz} cast resolves that wall-clock midnight in the database session time zone.
 */
@Repository
class JdbcPriorityStateRepository implements PriorityStateRepository {

    /**
     * The fixed deadline horizon {@code H} (days) the raw urgency ramp is anchored to: urgency starts
     * rising only once the deadline is within {@code H} days of now (Daniel, Comité 2026-07-09). A
     * calibrable domain constant — replacing the old creation-anchored ramp that pinned every old task
     * at maximum urgency permanently.
     */
    private static final int URGENCY_HORIZON_DAYS = 7;

    /**
     * The deadline instant of a single cycle in the structural chain, on the 0–6 urgency ramp's time
     * axis. {@code core_cycle.end_date} is a {@code DATE}; the commitment is not overdue until the due
     * day has ended, so the instant is the start of the day <em>after</em> {@code end_date}. The
     * {@code ::timestamptz} cast resolves that midnight in the database session time zone (see the
     * class Javadoc — an explicit assumption pending Daniel's validation). {@code chain} is the joined
     * chain-cycle alias in both deadline CTEs.
     */
    private static final String CYCLE_DEADLINE_INSTANT = "(chain.end_date + INTERVAL '1 day')::timestamptz";

    /**
     * The deadline-anchored raw urgency expression on the 0–6 source scale, over the precomputed
     * {@code cd.deadline_instant} (ADR-026): the earliest end-of-day deadline across the executable's
     * whole structural cycle chain — its own cycle plus every ancestor reached via
     * {@code parent_cycle_id}. Only {@code ACTIVE} cycles contribute a deadline (Daniel, ADR-026): a
     * {@code COMPLETED} ancestor is walked through structurally but its {@code end_date} is ineligible
     * for the {@code MIN}, so a finished commitment with a past due date never injects a spurious
     * overdue deadline — same single criterion as {@link #FIND_ALIGNMENT_CONTEXTS_SQL}. Let
     * {@code days_to_deadline = (deadline_instant − now)} in days; then
     * {@code urgency = 5 · clamp(1 − days_to_deadline / H, 0, 1)} — {@code 0} while the deadline is more
     * than {@code H} days away, ramping to {@code 5} at the deadline, and rising above {@code 5} up to
     * the cap {@code 6} once overdue. Executables whose chain carries no {@code end_date} on any active
     * cycle (or that have no cycle) carry no urgency signal ({@code 0}). Shared by the day query and the
     * single-executable {@code rescore} read so both derive urgency identically (DRY guarantee).
     */
    private static final String URGENCY_RAW_EXPR = """
        CASE
            WHEN cd.deadline_instant IS NULL THEN 0
            ELSE LEAST(
                6,
                5 * GREATEST(
                    0,
                    1 - (EXTRACT(EPOCH FROM (cd.deadline_instant - now())) / 86400.0)
                        / %d
                )
            )
        END AS urgency_raw
        """.formatted(URGENCY_HORIZON_DAYS);

    /**
     * The day's schedulable executables with their raw factors. The recursive {@code cycle_chain} walks
     * {@code parent_cycle_id} upward from every user cycle (same structural walk, cycle guard and
     * depth bound 16 as {@link #FIND_ALIGNMENT_CONTEXTS_SQL}); {@code cycle_deadline} then keeps the
     * earliest {@code end_date} across each chain <em>among its {@code ACTIVE} cycles only</em>, so a
     * {@code COMPLETED} ancestor is traversed but does not lend its (possibly past) due date. Urgency is
     * derived over that deadline (see {@link #URGENCY_RAW_EXPR}). SYSTEM-generated
     * accounting rows and read-only AGENDA blocks are excluded — they are not the user's actionable
     * work. Parameters: the walk's user filter and the projection's user filter (both the same user id).
     */
    private static final String FIND_TODAYS_FACTORS_SQL = """
        WITH RECURSIVE cycle_chain (source_id, current_id, depth, path) AS (
            SELECT c.id, c.id, 0, ARRAY[c.id]
            FROM core_cycle c
            WHERE c.user_id = ?
            UNION ALL
            SELECT cc.source_id, parent.id, cc.depth + 1, cc.path || parent.id
            FROM cycle_chain cc
            JOIN core_cycle child ON child.id = cc.current_id
            JOIN core_cycle parent ON parent.id = child.parent_cycle_id
            WHERE cc.depth < 16
              AND NOT parent.id = ANY(cc.path)
        ),
        cycle_deadline AS (
            SELECT cc.source_id AS cycle_id,
                   MIN(%s) AS deadline_instant
            FROM cycle_chain cc
            JOIN core_cycle chain ON chain.id = cc.current_id
            WHERE chain.status = 'ACTIVE'
            GROUP BY cc.source_id
        )
        SELECT e.id,
               e.cycle_id,
               p.impact,
               e.effort_score,
               %s
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        LEFT JOIN cycle_deadline cd ON cd.cycle_id = e.cycle_id
        WHERE e.user_id = ?
          AND e.status IN ('TODO', 'IN_PROGRESS')
          AND e.type NOT IN ('AGENDA', 'TIME_BLOCK')
          AND e.system_generated = false
        """.formatted(CYCLE_DEADLINE_INSTANT, URGENCY_RAW_EXPR);

    /**
     * The same factor projection for one executable, applying the identical exclusions (read-only
     * AGENDA and system-generated rows carry no priority signal) so {@code rescore} of such a row is
     * a clean no-op rather than a spurious zero score. {@code cycle_chain} walks upward from the row's
     * own cycle (single-source specialization of the day query's walk); {@code cycle_deadline} reduces
     * the chain to its earliest {@code end_date} among its {@code ACTIVE} cycles only. Parameters: the executable id resolving the starting
     * cycle, then the executable id of the projected row (the same id, bound twice).
     */
    private static final String FIND_FACTORS_BY_ID_SQL = """
        WITH RECURSIVE cycle_chain (current_id, depth, path) AS (
            SELECT c.id, 0, ARRAY[c.id]
            FROM core_cycle c
            WHERE c.id = (SELECT cycle_id FROM core_executable WHERE id = ?)
            UNION ALL
            SELECT parent.id, cc.depth + 1, cc.path || parent.id
            FROM cycle_chain cc
            JOIN core_cycle child ON child.id = cc.current_id
            JOIN core_cycle parent ON parent.id = child.parent_cycle_id
            WHERE cc.depth < 16
              AND NOT parent.id = ANY(cc.path)
        ),
        cycle_deadline AS (
            SELECT MIN(%s) AS deadline_instant
            FROM cycle_chain cc
            JOIN core_cycle chain ON chain.id = cc.current_id
            WHERE chain.status = 'ACTIVE'
        )
        SELECT e.id,
               e.cycle_id,
               p.impact,
               e.effort_score,
               %s
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        LEFT JOIN cycle_deadline cd ON true
        WHERE e.id = ?
          AND e.type NOT IN ('AGENDA', 'TIME_BLOCK')
          AND e.system_generated = false
        """.formatted(CYCLE_DEADLINE_INSTANT, URGENCY_RAW_EXPR);

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

    /**
     * The graded-alignment context for a single source cycle, the single-source specialization of
     * {@link #FIND_ALIGNMENT_CONTEXTS_SQL} used by the on-event {@code rescore}. Same recursive walk,
     * same cycle guard and depth bound, scoped to one starting cycle.
     */
    private static final String FIND_ALIGNMENT_CONTEXT_BY_CYCLE_SQL = """
        WITH RECURSIVE walk (current_id, depth, path) AS (
            SELECT c.id, 0, ARRAY[c.id]
            FROM core_cycle c
            WHERE c.id = ?
            UNION ALL
            SELECT parent.id, w.depth + 1, w.path || parent.id
            FROM walk w
            JOIN core_cycle child ON child.id = w.current_id
            JOIN core_cycle parent ON parent.id = child.parent_cycle_id
            WHERE w.depth < 16
              AND NOT parent.id = ANY(w.path)
        ),
        ancestor_link AS (
            SELECT anc.type AS ancestor_type,
                   MIN(w.depth) AS distance
            FROM walk w
            JOIN core_cycle anc ON anc.id = w.current_id
            WHERE anc.status = 'ACTIVE'
            GROUP BY anc.type
        )
        SELECT src.type AS own_type,
               al.ancestor_type,
               al.distance
        FROM core_cycle src
        LEFT JOIN ancestor_link al ON true
        WHERE src.id = ?
        """;

    /**
     * Reads the currently persisted score pair of one executable, used to diff against a freshly
     * computed score before writing (epsilon-guarded change detection).
     */
    private static final String FIND_PERSISTED_SCORE_SQL =
        "SELECT priority_score, urgency_score FROM core_executable WHERE id = ?";

    private static final String SAVE_SCORE_SQL = """
        UPDATE core_executable
        SET priority_score = ?, urgency_score = ?, priority_computed_at = now()
        WHERE id = ?
        """;

    /**
     * The epsilon below which two scores count as unchanged; both {@code priority_score} ([0, 1]) and
     * the raw {@code urgency_score} (0–6) are compared with it. Small enough to catch every meaningful
     * move, large enough to absorb {@code double} round-off from repeated recomputation.
     */
    private static final double SCORE_EPSILON = 1e-9;

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
        // Bound twice: the recursive walk's user filter and the projection's user filter.
        return jdbcTemplate.query(FIND_TODAYS_FACTORS_SQL, FACTORS_MAPPER, userId, userId);
    }

    @Override
    public Optional<ExecutableFactors> findFactors(UUID executableId) {
        // Bound twice: the subquery resolving the starting cycle and the projected row's id filter.
        return jdbcTemplate.query(FIND_FACTORS_BY_ID_SQL, FACTORS_MAPPER, executableId, executableId)
            .stream()
            .findFirst();
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
    public Optional<CycleAlignmentContext> findAlignmentContext(UUID cycleId) {
        List<AncestorLink> links = new ArrayList<>();
        CycleType[] ownType = new CycleType[1];
        boolean[] found = new boolean[1];

        jdbcTemplate.query(FIND_ALIGNMENT_CONTEXT_BY_CYCLE_SQL, rs -> {
            found[0] = true;
            ownType[0] = CycleType.valueOf(rs.getString("own_type"));
            String ancestorType = rs.getString("ancestor_type");
            if (ancestorType != null) {
                links.add(new AncestorLink(CycleType.valueOf(ancestorType), rs.getInt("distance")));
            }
        }, cycleId, cycleId);

        return found[0]
            ? Optional.of(new CycleAlignmentContext(ownType[0], links))
            : Optional.empty();
    }

    @Override
    public Set<UUID> saveScores(List<PriorityScore> scores) {
        Set<UUID> changed = new HashSet<>();
        for (PriorityScore score : scores) {
            if (persistIfChanged(score)) {
                changed.add(score.executableId());
            }
        }
        return changed;
    }

    /**
     * Persists one score only when it differs (beyond {@link #SCORE_EPSILON}) from the currently
     * stored pair, so an unchanged executable is neither rewritten nor re-stamped and stays out of
     * the changed set that drives propagation.
     */
    private boolean persistIfChanged(PriorityScore score) {
        List<double[]> current = jdbcTemplate.query(FIND_PERSISTED_SCORE_SQL,
            (rs, rowNum) -> new double[]{
                rs.getObject("priority_score") != null ? rs.getDouble("priority_score") : Double.NaN,
                rs.getObject("urgency_score") != null ? rs.getDouble("urgency_score") : Double.NaN},
            score.executableId());
        if (current.isEmpty()) {
            // The row is not persisted yet (a CREATE scored before its upsert); nothing to update.
            return false;
        }
        if (unchanged(current.get(0)[0], score.score())
            && unchanged(current.get(0)[1], score.urgency())) {
            return false;
        }
        jdbcTemplate.update(SAVE_SCORE_SQL, score.score(), score.urgency(), score.executableId());
        return true;
    }

    /** A stored value equals a fresh one when both are present and within epsilon; NaN = never set. */
    private static boolean unchanged(double stored, double fresh) {
        return !Double.isNaN(stored) && Math.abs(stored - fresh) < SCORE_EPSILON;
    }
}
