package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.LearnedUnitCost;
import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.model.PlannerBlockIdentity;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.model.SleepWindow;
import com.hyperbrain.planner.domain.port.out.LearnedCostRepository;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.LearnedUnitCostCalculator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JDBC adapter for {@link PlannerStateRepository} (#6a). Reads the day's state straight from the
 * aggregates and persists the resulting {@code PLANNED} blocks — no ORM, mirroring
 * {@code JdbcPriorityStateRepository}.
 *
 * <p><b>Sleep frontier.</b> Wake is a record's {@code end_time}, bedtime its {@code start_time},
 * projected onto local wall-clock times of day in the user's {@code sys_user.timezone}. The freshness
 * guard is applied in SQL (most-recent record within the bound, else the whole history is dropped and
 * the settings fallback wins). {@code planner_constraints.sleep_window} lives in
 * {@code sys_user.settings} as {@code {"wake": "HH:mm", "bedtime": "HH:mm"}}; a hard default is used
 * when settings do not carry it.
 *
 * <p><b>Remaining effort.</b> For tasks with user subtasks the adapter resolves {@code cu} via the
 * {@link LearnedUnitCostCalculator} over the settled-block history ({@link LearnedCostRepository}), so
 * the with-subtasks branch reuses the exact spike-#63 estimator rather than re-deriving it.
 */
@Repository
class JdbcPlannerStateRepository implements PlannerStateRepository {

    private static final LocalTime DEFAULT_WAKE = LocalTime.of(7, 0);
    private static final LocalTime DEFAULT_BEDTIME = LocalTime.of(23, 0);

    /**
     * A tiny positive remaining-fraction for an overdue MCI: it keeps {@link MciWig}'s {@code (0, 1]}
     * invariant while letting the domain's {@code max(remainingFraction, ε)} + pace cap bound the
     * resulting required pace (F1.4 overdue case).
     */
    private static final double OVERDUE_REMAINING_FLOOR = 1e-6;

    private static final String TIMEZONE_SQL =
        "SELECT timezone FROM sys_user WHERE id = ?";

    private static final String SLEEP_WINDOW_SETTINGS_SQL = """
        SELECT settings #>> '{planner_constraints,sleep_window,wake}'    AS wake,
               settings #>> '{planner_constraints,sleep_window,bedtime}' AS bedtime
        FROM sys_user
        WHERE id = ?
        """;

    /**
     * Sleep records inside the history window whose most-recent sibling is fresh. The freshness guard
     * is a whole-history gate: if the newest record is older than the bound, the guarded subquery
     * yields nothing and the caller falls back to settings. {@code end_time}/{@code start_time} are
     * projected to the user's local wall-clock so the circular median runs on times of day.
     *
     * <p>Manual score-only rows (HU-01b slice 2) carry {@code end_time IS NULL}: the sample filter
     * drops them, and the freshness guard also ignores them ({@code fresh.end_time IS NOT NULL}) so
     * a fresh manual score can never vouch for stale hour history — the frontier stays fed
     * exclusively by records that observed real bedtime/wake instants.
     *
     * <p>Both freshness bounds are measured from the caller's injected {@code now} (bound as a
     * parameter), never the DB server clock, so the read is a pure function of the reference instant.
     */
    private static final String SLEEP_SAMPLES_SQL = """
        SELECT EXTRACT(HOUR   FROM (s.end_time   AT TIME ZONE ?))::int * 60
                 + EXTRACT(MINUTE FROM (s.end_time   AT TIME ZONE ?))::int AS wake_minutes,
               EXTRACT(HOUR   FROM (s.start_time AT TIME ZONE ?))::int * 60
                 + EXTRACT(MINUTE FROM (s.start_time AT TIME ZONE ?))::int AS bedtime_minutes
        FROM tel_sleep_record s
        WHERE s.user_id = ?
          AND s.end_time IS NOT NULL
          AND s.start_time >= ? - make_interval(days => ?)
          AND EXISTS (
              SELECT 1 FROM tel_sleep_record fresh
              WHERE fresh.user_id = s.user_id
                AND fresh.end_time IS NOT NULL
              GROUP BY fresh.user_id
              HAVING max(fresh.collected_at) >= ? - make_interval(hours => ?)
          )
        """;

    /**
     * Last night's score for the energy resolution. Device precedence (Daniel, 2026-07-11):
     * within the freshness bound, a record with real hours ({@code end_time IS NOT NULL}, e.g.
     * future HealthKit) wins over a manual score-only marker even when the marker's
     * {@code collected_at} is fresher — recency only breaks ties within the same class. The
     * freshness bound is measured from the caller's injected {@code now}, never the DB server clock.
     */
    private static final String LAST_NIGHT_SCORE_SQL = """
        SELECT s.sleep_score
        FROM tel_sleep_record s
        WHERE s.user_id = ?
          AND s.sleep_score IS NOT NULL
          AND s.collected_at >= ? - make_interval(hours => ?)
        ORDER BY (s.end_time IS NOT NULL) DESC, s.collected_at DESC
        LIMIT 1
        """;

    /**
     * The day's schedulable executables ranked by the persisted {@code priority_score} (highest
     * first). System-generated accounting rows and read-only AGENDA blocks are excluded — they are not
     * the user's schedulable work; the AGENDA windows re-enter as walls via
     * {@link #loadOccupiedIntervals}. {@code pending_subtasks} and {@code settled_actual} are derived
     * so the domain can size each block by remaining effort without a second round-trip.
     *
     * <p><b>Completed work is dropped so a (re)plan never re-schedules what is already done.</b> Two
     * completion signals are honoured: a terminal {@code status = DONE} (a checked-off task), and a
     * {@code last_completed_at} inside the target day (the completion clock stamped when work is done).
     * The second guard is the intraday-replan fix: a recurring/habit executable checked off today keeps
     * a live status but must not reappear on today's plan, while staying schedulable on future days
     * (then its completion clock is before the day). Both {@code dayStart}/{@code dayEnd} bound the
     * day in the caller's timezone, so the read is a pure function of the target day.
     */
    private static final String RANKED_EXECUTABLES_SQL = """
        SELECT e.id,
               e.type,
               e.cycle_id,
               e.priority_score,
               (e.status = 'IN_PROGRESS')                       AS in_progress,
               p.energy_drain,
               COALESCE(
                   p.estimated_minutes,
                   CASE WHEN e.end_time IS NOT NULL
                             AND e.start_time IS NOT NULL
                             AND e.end_time > e.start_time
                        THEN GREATEST(1, (EXTRACT(EPOCH FROM (e.end_time - e.start_time)) / 60)::integer)
                        ELSE NULL
                   END
               )                                                AS estimated_minutes,
               (SELECT count(*) FROM core_executable sub
                WHERE sub.parent_id = e.id
                  AND sub.system_generated = false
                  AND sub.status IN ('TODO', 'IN_PROGRESS', 'WAITING')) AS pending_subtasks,
               (SELECT count(*) FROM core_executable sub
                WHERE sub.parent_id = e.id
                  AND sub.system_generated = false)              AS total_subtasks,
               COALESCE((SELECT sum(b.actual_duration_minutes) FROM core_time_block b
                WHERE b.executable_id = e.id
                  AND b.actual_duration_minutes IS NOT NULL), 0) AS settled_actual,
               e.end_time                                        AS due_instant
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.user_id = ?
          AND e.status IN ('TODO', 'IN_PROGRESS')
          AND e.type <> 'AGENDA'
          AND e.system_generated = false
          AND (e.last_completed_at IS NULL
               OR e.last_completed_at <  ?
               OR e.last_completed_at >= ?)
        ORDER BY e.priority_score DESC NULLS LAST, e.id
        """;

    /**
     * The WIG portfolio: one row per ACTIVE MCI cycle (F1), computed in a single aggregated pass — no
     * N+1 per MCI. The query walks four CTEs:
     * <ul>
     *   <li>{@code mci} — the user's ACTIVE MCI cycles;</li>
     *   <li>{@code subtree} — each MCI's cycle subtree by {@code parent_cycle_id} (recursive), so
     *       aggregated progress spans the whole hierarchy under the MCI;</li>
     *   <li>{@code progress} — the {@code estimated_minutes}-weighted mean effective progress over the
     *       <b>root</b> executables ({@code parent_id IS NULL}, {@code system_generated = false}) whose
     *       {@code cycle_id} is in the subtree. Effective progress: DONE → 1.0; a non-DONE row with a
     *       non-null {@code progress} → that value; otherwise 0.0 (NULL is never propagated to the
     *       aggregate). Rows with NULL/0 {@code estimated_minutes} are excluded from the weighting;
     *       when no row qualifies the weighted mean is 0 (all still to do);</li>
     *   <li>{@code lead} — the lead measure to reserve: the highest-priority not-DONE LEAD_MEASURE in
     *       the subtree ({@code priority_score DESC NULLS LAST, id}); null when the MCI has none, which
     *       the domain turns into a 4DX-D2 alert.</li>
     * </ul>
     * Remaining fraction is clamped to the borderline defaults: {@code (1.0]} normally; {@code 1.0}
     * when the MCI has no {@code start_date}/{@code end_date} (no temporal pressure); a small positive
     * floor when overdue (the domain caps the resulting pace). The hysteresis flag
     * ({@code received_block_yesterday}) and the release-valve streak ({@code degraded_days_without_block},
     * a bounded consecutive block-less day count) come from the lead measure's recent PLANNER blocks.
     * The reference day is the caller's {@code now} projected to the user's timezone (bound as a
     * parameter), so the read is a pure function of the reference instant — never the DB server clock.
     */
    private static final String WIG_PORTFOLIO_SQL = """
        WITH RECURSIVE mci AS (
            SELECT c.id, c.start_date, c.end_date, c.status
            FROM core_cycle c
            WHERE c.user_id = ?
              AND c.type = 'MCI'
              AND c.status = 'ACTIVE'
        ),
        subtree AS (
            SELECT m.id AS mci_id, m.id AS cycle_id
            FROM mci m
            UNION
            SELECT s.mci_id, child.id
            FROM subtree s
            JOIN core_cycle child ON child.parent_cycle_id = s.cycle_id
        ),
        progress AS (
            SELECT s.mci_id,
                   sum(
                       CASE WHEN e.status = 'DONE' THEN 1.0
                            WHEN e.progress IS NOT NULL THEN e.progress
                            ELSE 0.0 END
                       * e.est_minutes
                   ) AS weighted_sum,
                   sum(e.est_minutes) AS weight_total
            FROM subtree s
            JOIN (
                SELECT ex.cycle_id, ex.status, ex.progress, p.estimated_minutes AS est_minutes
                FROM core_executable ex
                JOIN core_execution_profile p ON p.executable_id = ex.id
                WHERE ex.parent_id IS NULL
                  AND ex.system_generated = false
                  AND p.estimated_minutes IS NOT NULL
                  AND p.estimated_minutes > 0
            ) e ON e.cycle_id = s.cycle_id
            GROUP BY s.mci_id
        ),
        lead AS (
            SELECT DISTINCT ON (s.mci_id) s.mci_id, lm.id AS lead_measure_id
            FROM subtree s
            JOIN core_executable lm ON lm.cycle_id = s.cycle_id
            WHERE lm.type = 'LEAD_MEASURE'
              AND lm.system_generated = false
              AND lm.status IN ('TODO', 'IN_PROGRESS', 'WAITING')
            ORDER BY s.mci_id, lm.priority_score DESC NULLS LAST, lm.id
        )
        SELECT m.id                                        AS mci_id,
               l.lead_measure_id                           AS lead_measure_id,
               COALESCE(pr.weighted_sum / NULLIF(pr.weight_total, 0), 0.0) AS aggregated_progress,
               m.start_date                                AS start_date,
               m.end_date                                  AS end_date,
               (m.status = 'COMPLETED')                    AS completed,
               EXISTS (
                   SELECT 1 FROM core_time_block b
                   WHERE b.executable_id = l.lead_measure_id
                     AND b.origin = 'PLANNER'
                     AND (b.date_start AT TIME ZONE ?)::date = ?::date - 1
               )                                           AS received_block_yesterday,
               LEAST(
                   ?,
                   COALESCE((
                       SELECT min(?::date - (b.date_start AT TIME ZONE ?)::date) - 1
                       FROM core_time_block b
                       WHERE b.executable_id = l.lead_measure_id
                         AND b.origin = 'PLANNER'
                         AND (b.date_start AT TIME ZONE ?)::date < ?::date
                   ), ?)
               )                                           AS degraded_days_without_block
        FROM mci m
        LEFT JOIN progress pr ON pr.mci_id = m.id
        LEFT JOIN lead l ON l.mci_id = m.id
        """;

    /** Open/settled blocks overlapping the day — hard walls the Planner never schedules over. */
    private static final String OCCUPIED_BLOCKS_SQL = """
        SELECT b.executable_id,
               b.date_start,
               COALESCE(b.date_end, b.settled_at, b.date_start + interval '1 minute') AS date_end
        FROM core_time_block b
        JOIN core_executable e ON e.id = b.executable_id
        WHERE e.user_id = ?
          AND b.status IN ('PLANNED', 'ACTIVE', 'SETTLED')
          AND b.date_start < ?
          AND COALESCE(b.date_end, b.settled_at, b.date_start + interval '1 minute') > ?
        """;

    /** Read-only AGENDA executable windows overlapping the day (ADR-009) — walls, never editable. */
    private static final String OCCUPIED_AGENDA_SQL = """
        SELECT e.id,
               e.start_time,
               e.end_time
        FROM core_executable e
        WHERE e.user_id = ?
          AND e.type = 'AGENDA'
          AND e.start_time IS NOT NULL
          AND e.end_time IS NOT NULL
          AND e.start_time < ?
          AND e.end_time > ?
        """;

    /**
     * Upserts a desired block under its <b>stable id</b>: a brand-new id inserts, a surviving id
     * updates in place (so the block keeps its {@code sync_mapping} → the write-back emits an UPDATE,
     * not a duplicate CREATE, #15). The {@code ON CONFLICT} update is guarded to
     * {@code PLANNED}/{@code PLANNER} rows so a stable id that happens to collide with an
     * {@code ACTIVE}/{@code SETTLED} block (already-started work, telemetry-bearing) never clobbers it —
     * the conflicting desired block is silently skipped, which is correct: that executable is already
     * being worked on today.
     */
    private static final String UPSERT_BLOCK_SQL = """
        INSERT INTO core_time_block
            (id, executable_id, date_start, date_end, status, origin, planned_minutes, reason)
        VALUES (?, ?, ?, ?, 'PLANNED', 'PLANNER', ?, ?)
        ON CONFLICT (id) DO UPDATE
           SET date_start      = EXCLUDED.date_start,
               date_end        = EXCLUDED.date_end,
               planned_minutes = EXCLUDED.planned_minutes,
               reason          = EXCLUDED.reason
         WHERE core_time_block.status = 'PLANNED'
           AND core_time_block.origin = 'PLANNER'
        """;

    /** The day's regenerable planner block ids — the reconciliation universe (survive vs. removed). */
    private static final String EXISTING_PLANNED_IDS_SQL = """
        SELECT b.id
        FROM core_time_block b
        JOIN core_executable e ON e.id = b.executable_id
        WHERE e.user_id = ?
          AND b.status = 'PLANNED'
          AND b.origin = 'PLANNER'
          AND b.date_start >= ?
          AND b.date_start < ?
        """;

    /**
     * Deletes a single regenerable block by id when it dropped out of the new plan. Scoped to
     * {@code PLANNED}/{@code PLANNER} so {@code FOCUS}/{@code USER} blocks and settled work survive.
     */
    private static final String DELETE_REMOVED_BLOCK_SQL = """
        DELETE FROM core_time_block
        WHERE id = ?
          AND status = 'PLANNED'
          AND origin = 'PLANNER'
        """;

    /** Re-reads the day's persisted planner blocks with their executable names for the write-back. */
    private static final String PLANNED_BLOCKS_FOR_DAY_SQL = """
        SELECT b.id,
               b.executable_id,
               e.name AS executable_name,
               b.date_start,
               COALESCE(b.date_end, b.date_start + interval '1 minute') AS date_end,
               b.reason
        FROM core_time_block b
        JOIN core_executable e ON e.id = b.executable_id
        WHERE e.user_id = ?
          AND b.status = 'PLANNED'
          AND b.origin = 'PLANNER'
          AND b.date_start >= ?
          AND b.date_start < ?
        ORDER BY b.date_start, b.id
        """;

    private final JdbcTemplate jdbcTemplate;
    private final LearnedCostRepository learnedCostRepository;
    private final LearnedUnitCostCalculator learnedUnitCostCalculator;
    private final PlannerConstraints constraints;

    JdbcPlannerStateRepository(JdbcTemplate jdbcTemplate,
                               LearnedCostRepository learnedCostRepository,
                               LearnedUnitCostCalculator learnedUnitCostCalculator,
                               PlannerConstraints constraints) {
        this.jdbcTemplate = jdbcTemplate;
        this.learnedCostRepository = learnedCostRepository;
        this.learnedUnitCostCalculator = learnedUnitCostCalculator;
        this.constraints = constraints;
    }

    @Override
    public ZoneId loadUserZone(UUID userId) {
        return ZoneId.of(jdbcTemplate.queryForObject(TIMEZONE_SQL, String.class, userId));
    }

    @Override
    public SleepFrontierInputs loadSleepFrontierInputs(UUID userId, OffsetDateTime now) {
        String timezone = jdbcTemplate.queryForObject(TIMEZONE_SQL, String.class, userId);

        List<LocalTimeOfDay> wakeSamples = new ArrayList<>();
        List<LocalTimeOfDay> bedtimeSamples = new ArrayList<>();
        jdbcTemplate.query(SLEEP_SAMPLES_SQL, rs -> {
            wakeSamples.add(new LocalTimeOfDay(rs.getInt("wake_minutes")));
            bedtimeSamples.add(new LocalTimeOfDay(rs.getInt("bedtime_minutes")));
        }, timezone, timezone, timezone, timezone, userId,
            now, constraints.sleepHistoryDays(), now, constraints.sleepFreshnessHours());

        return new SleepFrontierInputs(wakeSamples, bedtimeSamples, loadFallbackWindow(userId));
    }

    @Override
    public Integer loadLastNightSleepScore(UUID userId, OffsetDateTime now) {
        return jdbcTemplate.query(LAST_NIGHT_SCORE_SQL,
                (rs, rowNum) -> rs.getObject("sleep_score", Integer.class),
                userId, now, constraints.sleepFreshnessHours())
            .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    @Override
    public List<SchedulableExecutable> loadRankedExecutables(UUID userId, OffsetDateTime dayStart,
                                                             OffsetDateTime dayEnd) {
        return jdbcTemplate.query(RANKED_EXECUTABLES_SQL, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            int totalSubtasks = rs.getInt("total_subtasks");
            Double learnedCu = totalSubtasks > 0 ? resolveCu(id) : null;
            return new SchedulableExecutable(
                id,
                ExecutableType.valueOf(rs.getString("type")),
                rs.getObject("priority_score", Double.class),
                rs.getBoolean("in_progress"),
                rs.getObject("energy_drain", Integer.class),
                learnedCu,
                rs.getInt("pending_subtasks"),
                rs.getObject("estimated_minutes", Integer.class),
                rs.getInt("settled_actual"),
                rs.getObject("due_instant", OffsetDateTime.class),
                rs.getObject("cycle_id", UUID.class));
        }, userId, dayStart, dayEnd);
    }

    @Override
    public List<MciWig> loadWigPortfolio(UUID userId, OffsetDateTime now) {
        String timezone = jdbcTemplate.queryForObject(TIMEZONE_SQL, String.class, userId);
        LocalDate today = now.atZoneSameInstant(java.time.ZoneId.of(timezone)).toLocalDate();
        int streakBound = constraints.degradedStreakThreshold();

        return jdbcTemplate.query(WIG_PORTFOLIO_SQL, (rs, rowNum) -> {
            UUID leadMeasureId = rs.getObject("lead_measure_id", UUID.class);
            LocalDate startDate = toLocalDate(rs.getObject("start_date", java.sql.Date.class));
            LocalDate endDate = toLocalDate(rs.getObject("end_date", java.sql.Date.class));
            return new MciWig(
                rs.getObject("mci_id", UUID.class),
                leadMeasureId,
                rs.getDouble("aggregated_progress"),
                remainingFraction(startDate, endDate, today),
                rs.getBoolean("completed"),
                endDate,
                rs.getBoolean("received_block_yesterday"),
                rs.getInt("degraded_days_without_block"));
        },
            // mci CTE
            userId,
            // received_block_yesterday: tz(block date), today
            timezone, today,
            // degraded_days_without_block: bound, today(min), tz(block min), tz(block filter), today(filter), bound fallback
            streakBound, today, timezone, timezone, today, streakBound);
    }

    /**
     * The remaining-window fraction with the F1 borderline defaults: {@code 1.0} when the MCI has no
     * {@code start_date}/{@code end_date} (no temporal pressure); a small positive floor when overdue
     * (the domain caps the derived pace); otherwise {@code (end − today)/(end − start)} clamped to
     * {@code (0, 1]}.
     */
    private static double remainingFraction(LocalDate startDate, LocalDate endDate, LocalDate today) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            return 1.0;
        }
        double span = endDate.toEpochDay() - startDate.toEpochDay();
        double remaining = (endDate.toEpochDay() - today.toEpochDay()) / span;
        if (remaining <= 0.0) {
            return OVERDUE_REMAINING_FLOOR;
        }
        return Math.min(remaining, 1.0);
    }

    private static LocalDate toLocalDate(java.sql.Date date) {
        return date == null ? null : date.toLocalDate();
    }

    @Override
    public List<OccupiedInterval> loadOccupiedIntervals(UUID userId, OffsetDateTime dayStart,
                                                        OffsetDateTime dayEnd) {
        List<OccupiedInterval> intervals = new ArrayList<>(jdbcTemplate.query(OCCUPIED_BLOCKS_SQL,
            (rs, rowNum) -> new OccupiedInterval(
                rs.getObject("executable_id", UUID.class),
                rs.getObject("date_start", OffsetDateTime.class),
                rs.getObject("date_end", OffsetDateTime.class),
                false),
            userId, dayEnd, dayStart));

        intervals.addAll(jdbcTemplate.query(OCCUPIED_AGENDA_SQL,
            (rs, rowNum) -> new OccupiedInterval(
                rs.getObject("id", UUID.class),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                true),
            userId, dayEnd, dayStart));
        return intervals;
    }

    @Override
    public List<UUID> reconcilePlannedBlocks(UUID userId, LocalDate targetDay, ZoneId zone,
                                             List<AgendaBlock> desired) {
        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        List<UUID> existingIds =
            jdbcTemplate.queryForList(EXISTING_PLANNED_IDS_SQL, UUID.class, userId, dayStart, dayEnd);

        List<PlannerBlockIdentity.IdentifiedBlock> identified =
            PlannerBlockIdentity.assign(desired, targetDay);
        Set<UUID> desiredIds = identified.stream()
            .map(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .collect(Collectors.toSet());

        jdbcTemplate.batchUpdate(UPSERT_BLOCK_SQL, identified, identified.size(), (ps, entry) -> {
            AgendaBlock block = entry.block();
            ps.setObject(1, entry.blockId());
            ps.setObject(2, block.executableId());
            ps.setObject(3, block.start());
            ps.setObject(4, block.end());
            ps.setObject(5, (int) block.durationMinutes());
            ps.setString(6, block.reason());
        });

        List<UUID> removed = existingIds.stream()
            .filter(id -> !desiredIds.contains(id))
            .toList();
        if (!removed.isEmpty()) {
            jdbcTemplate.batchUpdate(DELETE_REMOVED_BLOCK_SQL, removed, removed.size(),
                (ps, id) -> ps.setObject(1, id));
        }
        return removed;
    }

    @Override
    public List<PlannedBlockRecord> loadPlannedBlocksForDay(UUID userId, LocalDate targetDay,
                                                            ZoneId zone) {
        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return jdbcTemplate.query(PLANNED_BLOCKS_FOR_DAY_SQL, (rs, rowNum) -> new PlannedBlockRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("executable_id", UUID.class),
                rs.getString("executable_name"),
                rs.getObject("date_start", OffsetDateTime.class),
                rs.getObject("date_end", OffsetDateTime.class),
                rs.getString("reason")),
            userId, dayStart, dayEnd);
    }

    private Double resolveCu(UUID taskId) {
        LearnedUnitCost cost = learnedUnitCostCalculator.calculate(
            learnedCostRepository.loadCostInputs(taskId));
        return cost.cu();
    }

    private SleepWindow loadFallbackWindow(UUID userId) {
        return jdbcTemplate.query(SLEEP_WINDOW_SETTINGS_SQL, rs -> {
            if (!rs.next()) {
                return SleepWindow.fallback(toTimeOfDay(DEFAULT_WAKE), toTimeOfDay(DEFAULT_BEDTIME));
            }
            LocalTime wake = parseOrDefault(rs.getString("wake"), DEFAULT_WAKE);
            LocalTime bedtime = parseOrDefault(rs.getString("bedtime"), DEFAULT_BEDTIME);
            return SleepWindow.fallback(toTimeOfDay(wake), toTimeOfDay(bedtime));
        }, userId);
    }

    private static LocalTime parseOrDefault(String value, LocalTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return LocalTime.parse(value.trim());
    }

    private static LocalTimeOfDay toTimeOfDay(LocalTime time) {
        return new LocalTimeOfDay(time.getHour() * 60 + time.getMinute());
    }
}
