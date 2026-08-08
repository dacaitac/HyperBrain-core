package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.MovableCommitment;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannedBlockMember;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.model.PlannerBlockIdentity;
import com.hyperbrain.planner.domain.model.PlannerBlockRow;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SleepFrontierInputs;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.model.SleepWindow;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * <p><b>Which block model this reads and writes.</b> All of it: a block is a {@code core_executable}
 * of type {@code TIME_BLOCK} (ADR-039). The frozen {@code core_time_block} table is not touched — the
 * last two reads that still addressed it, the executed-minutes sum and the reschedule seed, died with
 * the mechanisms they fed.
 */
@Repository
class JdbcPlannerStateRepository implements PlannerStateRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlannerStateRepository.class);

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
     * The rows whose sleep can still be relevant to the day being planned: those that ended within the
     * lookback. The sessions themselves live in {@code stages -> 'sessions'} (night + naps) because the
     * row has only two instant columns and they hold the main session; a row written before that array
     * existed — or by a payload of a single session — falls back to its own hours.
     */
    private static final String RECENT_SLEEP_SESSIONS_SQL = """
        SELECT start_time, end_time, duration_minutes, stages -> 'sessions' AS sessions
        FROM tel_sleep_record
        WHERE user_id = ?
          AND end_time IS NOT NULL
          AND end_time >= ? - make_interval(hours => ?)
        ORDER BY end_time
        """;

    /**
     * How far back a session may end and still speak about the day being planned: one day, which holds
     * last night and every nap since.
     */
    private static final int SESSION_LOOKBACK_HOURS = 24;

    /**
     * How far back a <em>row</em> is read. Wider than the session lookback because a row is stamped
     * with its main session's hours while its naps can end many hours later: a night that ended just
     * outside the session window may still carry an afternoon nap that falls inside it.
     */
    private static final int ROW_LOOKBACK_HOURS = 48;

    private static final long SECONDS_PER_MINUTE = 60L;

    /**
     * The day's schedulable executables ranked by the persisted {@code priority_score} (highest
     * first). System-generated accounting rows and read-only AGENDA blocks are excluded — they are not
     * the user's schedulable work; the AGENDA windows re-enter as walls via
     * {@link #loadOccupiedIntervals}. {@code pending_subtasks} and {@code settled_actual} are derived
     * so the domain can size each block by remaining effort without a second round-trip.
     *
     * <p><b>Due instant.</b> The target date is {@code COALESCE(end_time, start_time)}, mirroring the
     * outbound projection {@code end_time ?? start_time}: a TASK keeps its due date in {@code start_time}
     * (DR-01 forbids {@code end_time} on TASK), so reading {@code end_time} alone would miss it and plan
     * a future-dated task today. Calendar-backed rows carry {@code end_time}, so the coalesce is a no-op
     * for them.
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
               COALESCE(e.end_time, e.start_time)                AS due_instant
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.user_id = ?
          AND e.status IN ('TODO', 'IN_PROGRESS')
          -- AGENDA is the read-only wall (ADR-009); BUYING is a dateless shopping-list reminder
          -- that never enters the daily floor and is outside the ExecutableType planner mirror,
          -- so it must be filtered here before the row reaches ExecutableType.valueOf. TIME_BLOCK
          -- (ADR-039) is a scheduling container, never schedulable work — it is a wall, not a task.
          AND e.type NOT IN ('AGENDA', 'BUYING', 'TIME_BLOCK')
          AND e.system_generated = false
          AND (e.last_completed_at IS NULL
               OR e.last_completed_at <  ?
               OR e.last_completed_at >= ?)
          -- Work already held by a block this run may NOT re-time is not a candidate at all.
          -- Containment is monovalent, so merely offering it would silently move it out of the block
          -- that holds it — and by the time a row is written the move has already happened, which is
          -- why the guard has to live here and not in the persistence.
          --
          -- The rule is the exact complement of the regenerable set: the ONLY membership this run may
          -- take apart is the membership of the blocks it is about to re-place (see
          -- REGENERABLE_BLOCKS_SQL — planner-authored, still PLANNED, still ahead). Everything else
          -- holds what it holds: a USER block the user timed by hand (slot authority, ADR-026 D6), a
          -- block already under way or already closed (the past is never rewritten, ADR-040 D8) — and
          -- the block Daniel creates by hand in Notion, which is what a status list got wrong in
          -- production. That block is born through the ordinary ingestion of an executable, so it
          -- arrives as TODO; while the guard demanded PLANNED/IN_PROGRESS it did not recognise his
          -- blocks at all, and every replan emptied «WakeUP», «Oficio» and «Torbellino» and dealt
          -- their tasks around the day. As with the occupancy wall, no state of a real block means
          -- "this membership is free" — the only thing that means it is the block not being there.
          --
          -- Planner blocks still ahead are deliberately NOT guarded: those are the ones this run
          -- re-places, and walling them out would push the whole day onto tomorrow.
          --
          -- FOCUS is the single exclusion, exactly as it is in the occupancy read: a focus row is
          -- retrospective accounting of work already running, never somebody's arrangement, so it
          -- holds no authority over what it points at. It is written as IS DISTINCT FROM rather than
          -- a whitelist of origins because a block born in Notion carries no origin at all.
          AND NOT EXISTS (
              SELECT 1 FROM core_executable holder
              WHERE holder.id     = e.container_block_id
                AND holder.type   = 'TIME_BLOCK'
                AND holder.origin IS DISTINCT FROM 'FOCUS'
                AND NOT (holder.origin     = 'PLANNER'
                     AND holder.status     = 'PLANNED'
                     AND holder.start_time >= ?))
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
     * floor when overdue (the domain caps the resulting pace). The reference day is the caller's
     * {@code now} projected to the user's timezone (bound as a parameter), so the read is a pure
     * function of the reference instant — never the DB server clock.
     *
     * <p><b>Selector signals.</b> The hysteresis flag ({@code received_block_yesterday}) and the
     * release-valve streak ({@code degraded_days_without_block}, a bounded consecutive block-less day
     * count) answer one question — "did this lead measure get a window of time?" — read over the recent
     * past. They are derived from the deployed model (ADR-039): the lead measure is held by a
     * {@code TIME_BLOCK} executable through its own {@code container_block_id}, not by an anchor row in
     * the frozen {@code core_time_block} table. Containment is monovalent, so the trail is the lead
     * measure's <em>latest</em> container: that is exactly the most recent block, which is what the
     * streak measures, while the yesterday flag turns false once a same-day replan has already
     * re-contained the lead measure into today's block.
     *
     * <p><b>Who composed the window does not enter the question, and demanding {@code PLANNER} inverted
     * the answer.</b> {@code origin} says whose the block is to re-place, not whether a block is there.
     * A window is a window: one the planner laid and then handed over when Daniel put work in it by hand
     * ({@code USER}), and one he creates himself in Notion (no origin at all), both fed the goal exactly
     * as a planner block does. While the read demanded {@code PLANNER}, the night he spent feeding his
     * main goal by hand was read as famine: the flag came back false, the block-less streak saturated,
     * and the required pace and the release valve both fired — the system concluded the goal was starving
     * <em>because</em> he was tending it. {@code FOCUS} is the single exclusion, as it is in
     * {@link #OCCUPIED_BLOCKS_SQL} and in the candidate guard: a focus row is retrospective accounting of
     * work already running, never a window somebody reserved. It is written as {@code IS DISTINCT FROM}
     * rather than a whitelist because a block born in Notion carries no origin.
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
                  AND ex.type <> 'TIME_BLOCK'
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
                   SELECT 1
                   FROM core_executable lm
                   JOIN core_executable b ON b.id = lm.container_block_id
                   WHERE lm.id = l.lead_measure_id
                     AND b.type = 'TIME_BLOCK'
                     AND b.origin IS DISTINCT FROM 'FOCUS'
                     AND (b.start_time AT TIME ZONE ?)::date = ?::date - 1
               )                                           AS received_block_yesterday,
               LEAST(
                   ?,
                   COALESCE((
                       SELECT min(?::date - (b.start_time AT TIME ZONE ?)::date) - 1
                       FROM core_executable lm
                       JOIN core_executable b ON b.id = lm.container_block_id
                       WHERE lm.id = l.lead_measure_id
                         AND b.type = 'TIME_BLOCK'
                         AND b.origin IS DISTINCT FROM 'FOCUS'
                         AND (b.start_time AT TIME ZONE ?)::date < ?::date
                   ), ?)
               )                                           AS degraded_days_without_block
        FROM mci m
        LEFT JOIN progress pr ON pr.mci_id = m.id
        LEFT JOIN lead l ON l.mci_id = m.id
        """;

    /**
     * The day's real {@code TIME_BLOCK} executables — hard walls the Planner never schedules over.
     * Reads the deployed model (ADR-039: the block IS a {@code core_executable}), not the frozen
     * {@code core_time_block} table: blocks created from Notion or from the calendar live only here,
     * so anchoring the walls to the frozen table left the Planner blind to them.
     *
     * <p><b>Status does not decide whether a block walls, and it must not.</b> A block that is not focus
     * accounting holds real time in whatever state it is in: an open one ({@code PLANNED},
     * {@code IN_PROGRESS}) is the plan, a closed one ({@code DONE}, {@code FAILED}) occupied time that
     * has already gone by, and a {@code TODO} one is time the user reserved and has simply not started.
     * That last case is what a status list got wrong in production: a block Daniel creates by hand is
     * born from Notion through the ordinary ingestion of an executable, so it arrives as {@code TODO} —
     * and while {@code TODO} was missing from the list, his evening block was invisible as a wall and
     * the planner laid three windows straight on top of it. There is no state of a real block that
     * means "this time is free"; the only thing that means it is the block not being there.
     *
     * <p><b>Why {@code FOCUS} blocks do not wall.</b> A focus block is retrospective accounting of a
     * task that is already running, not planned occupancy: its window is either degenerate (auto-opened
     * with no {@code end_time}, hence the one-minute stub below) or entirely in the past. The occupancy
     * it accounts for belongs to the task's own container, which already walls. Focus is therefore the
     * single exclusion, and it is written as {@code IS DISTINCT FROM} rather than a whitelist because a
     * block Notion created carries no origin at all: a whitelist would have made those invisible too.
     *
     * <p>The end bound falls back to the settlement clock and then to a one-minute stub so an
     * open-ended block still yields the strictly-positive interval {@link OccupiedInterval} requires.
     */
    private static final String OCCUPIED_BLOCKS_SQL = """
        SELECT b.id,
               b.start_time,
               COALESCE(b.end_time, b.last_completed_at, b.start_time + interval '1 minute') AS end_time
        FROM core_executable b
        WHERE b.user_id = ?
          AND b.type = 'TIME_BLOCK'
          AND b.origin IS DISTINCT FROM 'FOCUS'
          AND b.start_time < ?
          AND COALESCE(b.end_time, b.last_completed_at, b.start_time + interval '1 minute') > ?
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
     * The day's commitments that hold time: the {@code ACTIVITY} and {@code LEARNING_SESSION} windows
     * overlapping the planning day. An activity is a calendar event, so it occupies by definition.
     *
     * <p><b>Why this read had to exist.</b> The middle tier of the rigidity hierarchy (ADR-040 D9) says a
     * commitment moves in hour, never in day — and <em>movable</em> was silently read as <em>invisible</em>.
     * A commitment is never a window member (the generator refuses it as {@code NOT_CONTAINABLE}: it
     * already is a block of time in itself), and until now it was not a wall either, so it belonged to
     * neither list and the day was laid straight over it. Production said it plainly: «Desayunar»
     * standing at 10:30–11:30 with «Descanso» at 10:30 and «Oficio» at 11:00 laid on top of it.
     *
     * <p><b>What a terminal state means here, and why it differs from a block.</b> A block walls in every
     * state because a closed block is time that has already gone by. A commitment is the user's own work
     * and he can settle it ahead of its hour: {@code DONE} or {@code FAILED} therefore <b>releases</b> the
     * window — an activity already done does not occupy the future. Everything else occupies, and the
     * predicate is written as an exclusion rather than a whitelist for the same reason
     * {@link #OCCUPIED_BLOCKS_SQL} is: a row ingested from Notion or the calendar arrives in whatever
     * state its origin gives it, and a whitelist is exactly what once made those rows invisible. For a
     * window with an owner, "taken" is the safe default.
     *
     * <p><b>The status set is deliberately wider than {@link #MOVABLE_COMMITMENTS_SQL}'s</b>, and the two
     * must not be merged: what occupies the day is everything not settled, while what the planner may
     * pick up and re-time is only the open work (ADR-040 D9 keeps that authority narrow). A commitment
     * that occupies without being rescuable simply stays where it is, which is the conservative outcome.
     *
     * <p>{@code system_generated} rows stay out for the same reason a {@code FOCUS} block does: they are
     * retrospective accounting of work, not somebody's reservation of time.
     */
    private static final String OCCUPIED_COMMITMENTS_SQL = """
        SELECT e.id, e.start_time, e.end_time
        FROM core_executable e
        WHERE e.user_id = ?
          AND e.type IN ('ACTIVITY', 'LEARNING_SESSION')
          AND e.status NOT IN ('DONE', 'FAILED')
          AND e.system_generated = false
          AND e.start_time IS NOT NULL
          AND e.end_time   IS NOT NULL
          AND e.end_time   > e.start_time
          AND e.start_time < ?
          AND e.end_time   > ?
        """;

    /**
     * The day's movable commitments (ADR-040 D9): open activities and study sessions whose window sits
     * on the target day. Both bounds are required — a row without a real window is not something whose
     * hour can be moved.
     */
    private static final String MOVABLE_COMMITMENTS_SQL = """
        SELECT e.id, e.start_time, e.end_time
        FROM core_executable e
        WHERE e.user_id = ?
          AND e.type IN ('ACTIVITY', 'LEARNING_SESSION')
          AND e.status IN ('TODO', 'IN_PROGRESS')
          AND e.system_generated = false
          AND e.start_time IS NOT NULL
          AND e.end_time   IS NOT NULL
          AND e.end_time > e.start_time
          AND e.start_time >= ?
          AND e.start_time <  ?
        ORDER BY e.start_time, e.id
        """;

    /**
     * Inserts or re-times a planner block under the id the reconciliation assigned it — on the deployed
     * model, where a block is a {@code core_executable} of type {@code TIME_BLOCK} (ADR-039). A fresh
     * surrogate inserts; a continued id updates in place, so the block keeps its {@code sync_mapping}
     * and its calendar event is UPDATED rather than duplicated (#15).
     *
     * <p>Three properties are load-bearing and none of them is decoration:
     * <ul>
     *   <li><b>The name is written as given, and the caller is the one that protects it.</b> Naming is
     *       the LLM's work and it survives a replan (ADR-040 D8) — but that rule lives where the two
     *       names can be compared, in {@code PlannerBlockMaterializer}, which sends back the block's
     *       current name whenever it must not change. Refusing the write here instead was what left a
     *       block published under a bad name stuck with it forever.</li>
     *   <li><b>The update is guarded to planner-authored, still-{@code PLANNED} rows</b>, so an id that
     *       collided with a {@code USER} block or with already-started work can never clobber it.</li>
     *   <li><b>Nothing is written when nothing moved</b> ({@code IS DISTINCT FROM} guards): the update
     *       count is therefore an exact "did this block really change", which is what lets the caller
     *       announce two blocks on a replan that moved two, instead of thirty (ADR-040 D17).</li>
     * </ul>
     */
    private static final String UPSERT_BLOCK_SQL = """
        INSERT INTO core_executable
            (id, user_id, name, description, type, status, origin,
             start_time, end_time, template_slot_id, system_generated)
        VALUES (?, ?, ?, ?, 'TIME_BLOCK', 'PLANNED', 'PLANNER', ?, ?, ?, false)
        ON CONFLICT (id) DO UPDATE
           SET name             = EXCLUDED.name,
               start_time       = EXCLUDED.start_time,
               end_time         = EXCLUDED.end_time,
               description      = EXCLUDED.description,
               template_slot_id = EXCLUDED.template_slot_id
         WHERE core_executable.type   = 'TIME_BLOCK'
           AND core_executable.origin = 'PLANNER'
           AND core_executable.status = 'PLANNED'
           AND (core_executable.name             IS DISTINCT FROM EXCLUDED.name
             OR core_executable.start_time       IS DISTINCT FROM EXCLUDED.start_time
             OR core_executable.end_time         IS DISTINCT FROM EXCLUDED.end_time
             OR core_executable.description      IS DISTINCT FROM EXCLUDED.description
             OR core_executable.template_slot_id IS DISTINCT FROM EXCLUDED.template_slot_id)
        """;

    private static final String CYCLE_ID_BY_NAME_SQL = """
        SELECT id
        FROM core_cycle
        WHERE user_id = ? AND name = ?
        ORDER BY id
        LIMIT 1
        """;

    /**
     * An activity of one cycle whose window overlaps a candidate one — the natural key of an observed
     * episode. Standard half-open overlap test ({@code existing.start < candidate.end} and
     * {@code existing.end > candidate.start}), so two episodes that merely touch do not match.
     */
    private static final String OVERLAPPING_ACTIVITY_SQL = """
        SELECT id
        FROM core_executable
        WHERE user_id  = ?
          AND cycle_id = ?
          AND type     = 'ACTIVITY'
          AND start_time IS NOT NULL
          AND end_time   IS NOT NULL
          AND start_time < ?
          AND end_time   > ?
        ORDER BY start_time, id
        LIMIT 1
        """;

    /**
     * Inserts an episode the system observed after the fact: already {@code DONE}, carrying the real
     * window it happened in. {@code system_generated} stays false because this row <b>is</b> meant to
     * reach Notion and the calendar — unlike the retrospective accounting rows, which are not.
     */
    private static final String INSERT_COMPLETED_ACTIVITY_SQL = """
        INSERT INTO core_executable
            (id, user_id, cycle_id, name, type, status, origin, start_time, end_time, system_generated)
        VALUES (?, ?, ?, ?, 'ACTIVITY', 'DONE', 'PLANNER', ?, ?, false)
        """;

    /**
     * Re-times an activity's end. The {@code IS DISTINCT FROM} guard makes the update count an exact
     * "did this really move", so a re-observation that changed nothing announces nothing.
     */
    private static final String RETIME_ACTIVITY_END_SQL = """
        UPDATE core_executable
           SET end_time = ?
         WHERE id   = ?
           AND type = 'ACTIVITY'
           AND end_time IS DISTINCT FROM ?
        """;

    /**
     * The day's regenerable blocks with the membership each currently holds — the reconciliation
     * universe and the starting point of the conservative replan (ADR-040 D8). Membership is read
     * straight off {@code container_block_id}: since ADR-039 there is no bridge table and no anchor row,
     * the block is the executable and its members point at it.
     *
     * <p>The join is a LEFT JOIN on purpose: under the window model a window is laid before anything is
     * put inside it, so an <b>empty</b> block is a legitimate row that must still reconcile by its slot.
     */
    private static final String REGENERABLE_BLOCKS_SQL = """
        SELECT b.id,
               b.template_slot_id,
               b.name,
               b.start_time,
               COALESCE(b.end_time, b.start_time + interval '1 minute') AS end_time,
               m.id AS member_id
        FROM core_executable b
        LEFT JOIN core_executable m ON m.container_block_id = b.id
        WHERE b.user_id = ?
          AND b.type   = 'TIME_BLOCK'
          AND b.origin = 'PLANNER'
          AND b.status = 'PLANNED'
          AND b.start_time >= ?
          AND b.start_time <  ?
          AND b.start_time >= ?
        ORDER BY b.start_time, b.id, m.container_ord NULLS LAST, m.id
        """;

    /**
     * Re-reads the day's persisted planner blocks with one row per member, each joined to its display
     * name, for the agenda write-back (HU-01b). A block with no members still yields one row (LEFT
     * JOIN), so an empty window is never silently lost from the delivery.
     *
     * <p><b>Here the {@code PLANNER} filter is deliberate, unlike in the two selector signals above.</b>
     * The signals ask "did this lead measure get a window?", where a window is a window whoever composed
     * it. This read asks a different question — "which of the day's blocks are mine to deliver?" — and
     * authorship is exactly the right answer to it: a block Daniel made in Notion, or one the planner
     * handed over to him, already lives in his satellites through the ordinary executable mirror, so
     * announcing it again would deliver him his own arrangement a second time. The same reasoning keeps
     * {@code status = 'PLANNED'}: what is delivered is the plan, not what already closed.
     *
     * <p>The read has no caller in {@code src/main}: the write-back that consumed it is not wired today,
     * and this is left in place with its contract intact rather than tightened on speculation. When a
     * caller returns, the question above is the one to re-ask against it.
     */
    private static final String PLANNED_BLOCKS_FOR_DAY_SQL = """
        SELECT b.id,
               b.name                                                    AS theme,
               b.start_time                                              AS date_start,
               COALESCE(b.end_time, b.start_time + interval '1 minute')  AS date_end,
               b.description                                             AS reason,
               m.id                                                      AS member_id,
               m.name                                                    AS member_name,
               COALESCE(m.container_planned_minutes, 0)                  AS member_minutes,
               COALESCE(m.container_ord, 0)                              AS member_ord
        FROM core_executable b
        LEFT JOIN core_executable m ON m.container_block_id = b.id
        WHERE b.user_id = ?
          AND b.type   = 'TIME_BLOCK'
          AND b.origin = 'PLANNER'
          AND b.status = 'PLANNED'
          AND b.start_time >= ?
          AND b.start_time <  ?
        ORDER BY b.start_time, b.id, member_ord
        """;

    /** Display names of a set of executables for the LLM-facing read model (#61, H3). */
    private static final String EXECUTABLE_TITLES_SQL =
        "SELECT id, name FROM core_executable WHERE id IN (%s)";

    /**
     * What a set of blocks currently holds, in container order — the membership half of the day the
     * user pre-organised, for the LLM-facing read model. Read straight off {@code container_block_id}
     * like every other membership read since ADR-039.
     */
    private static final String BLOCK_MEMBERS_SQL = """
        SELECT container_block_id AS block_id, id AS member_id
        FROM core_executable
        WHERE container_block_id IN (%s)
        ORDER BY container_block_id, container_ord NULLS LAST, id
        """;

    private final JdbcTemplate jdbcTemplate;
    private final PlannerConstraints constraints;
    private final ObjectMapper objectMapper;

    JdbcPlannerStateRepository(JdbcTemplate jdbcTemplate, PlannerConstraints constraints,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.constraints = constraints;
        this.objectMapper = objectMapper;
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
    public List<SleepSession> loadRecentSleepSessions(UUID userId, OffsetDateTime now) {
        OffsetDateTime earliestSessionEnd = now.minusHours(SESSION_LOOKBACK_HOURS);
        List<SleepSession> sessions = new ArrayList<>();
        jdbcTemplate.query(RECENT_SLEEP_SESSIONS_SQL, rs -> {
            sessions.addAll(readSessions(
                rs.getString("sessions"),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                rs.getObject("duration_minutes", Integer.class)));
        }, userId, now, ROW_LOOKBACK_HOURS);
        return sessions.stream()
            .filter(session -> session.end().isAfter(earliestSessionEnd))
            .sorted(Comparator.comparing(SleepSession::start))
            .toList();
    }

    /**
     * Reads one row's sessions out of its {@code stages -> 'sessions'} array, falling back to the row's
     * own hours when the array is absent or unusable — a row written before the array existed still
     * knows one thing for certain: the main session it stores in its two instant columns.
     */
    private List<SleepSession> readSessions(String rawSessions, OffsetDateTime rowStart,
                                            OffsetDateTime rowEnd, Integer durationMinutes) {
        List<SleepSession> sessions = new ArrayList<>();
        if (rawSessions != null) {
            try {
                for (JsonNode node : objectMapper.readTree(rawSessions)) {
                    SleepSession session = readSession(node);
                    if (session != null) {
                        sessions.add(session);
                    }
                }
            } catch (JsonProcessingException ex) {
                log.warn("Unreadable sleep sessions on a record ending {}: {}", rowEnd, ex.getMessage());
            }
        }
        if (!sessions.isEmpty()) {
            return sessions;
        }
        SleepSession fallback = session(rowStart, rowEnd,
            durationMinutes == null ? 0L : durationMinutes * SECONDS_PER_MINUTE);
        return fallback == null ? List.of() : List.of(fallback);
    }

    /** One session node, or null when it does not describe a usable interval (tolerant reader). */
    private static SleepSession readSession(JsonNode node) {
        return session(
            instantOrNull(node.path("start").asText(null)),
            instantOrNull(node.path("end").asText(null)),
            node.path("asleep_seconds").asLong());
    }

    /**
     * Builds a session defensively: an unusable interval yields null rather than an exception, and the
     * asleep seconds are clamped to the window so a summed duration read from an older row's
     * {@code duration_minutes} can never claim more sleep than the window holds.
     */
    private static SleepSession session(OffsetDateTime start, OffsetDateTime end, long asleepSeconds) {
        if (start == null || end == null || !end.isAfter(start)) {
            return null;
        }
        long windowSeconds = Duration.between(start, end).toSeconds();
        return new SleepSession(start, end, Math.min(Math.max(asleepSeconds, 0L), windowSeconds));
    }

    private static OffsetDateTime instantOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    @Override
    public List<SchedulableExecutable> loadRankedExecutables(UUID userId, OffsetDateTime dayStart,
                                                             OffsetDateTime dayEnd,
                                                             OffsetDateTime notBefore) {
        return jdbcTemplate.query(RANKED_EXECUTABLES_SQL, (rs, rowNum) -> new SchedulableExecutable(
                rs.getObject("id", UUID.class),
                ExecutableType.valueOf(rs.getString("type")),
                rs.getObject("priority_score", Double.class),
                rs.getBoolean("in_progress"),
                rs.getObject("energy_drain", Integer.class),
                rs.getInt("pending_subtasks"),
                rs.getObject("estimated_minutes", Integer.class),
                rs.getObject("due_instant", OffsetDateTime.class),
                rs.getObject("cycle_id", UUID.class)),
            userId, dayStart, dayEnd, notBefore);
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
        // The wall is identified by the block's own executable id: since ADR-039 the block IS the
        // executable, so there is no anchor row to point at any more.
        List<OccupiedInterval> intervals = new ArrayList<>(jdbcTemplate.query(OCCUPIED_BLOCKS_SQL,
            (rs, rowNum) -> new OccupiedInterval(
                rs.getObject("id", UUID.class),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                false),
            userId, dayEnd, dayStart));

        intervals.addAll(jdbcTemplate.query(OCCUPIED_AGENDA_SQL,
            (rs, rowNum) -> new OccupiedInterval(
                rs.getObject("id", UUID.class),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                true),
            userId, dayEnd, dayStart));

        // An activity or a study session is a calendar event of the user's, not read-only agenda: he may
        // still be sent its hour back by the rescue (ADR-040 D9), so it is not flagged readOnlyAgenda.
        // While it stands where it stands, it occupies.
        intervals.addAll(jdbcTemplate.query(OCCUPIED_COMMITMENTS_SQL,
            (rs, rowNum) -> new OccupiedInterval(
                rs.getObject("id", UUID.class),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                false),
            userId, dayEnd, dayStart));
        return intervals;
    }

    @Override
    public List<MovableCommitment> loadMovableCommitments(UUID userId, OffsetDateTime dayStart,
                                                          OffsetDateTime dayEnd) {
        return jdbcTemplate.query(MOVABLE_COMMITMENTS_SQL, (rs, rowNum) -> new MovableCommitment(
            rs.getObject("id", UUID.class),
            rs.getObject("start_time", OffsetDateTime.class),
            rs.getObject("end_time", OffsetDateTime.class)), userId, dayStart, dayEnd);
    }

    @Override
    public List<PlannerBlockIdentity.PersistedBlock> loadRegenerableBlocks(
        UUID userId, LocalDate targetDay, ZoneId zone, OffsetDateTime notBefore) {
        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        Map<UUID, PersistedBlockAccumulator> accumulators = new LinkedHashMap<>();
        jdbcTemplate.query(REGENERABLE_BLOCKS_SQL, rs -> {
            UUID blockId = rs.getObject("id", UUID.class);
            PersistedBlockAccumulator accumulator = accumulators.get(blockId);
            if (accumulator == null) {
                accumulator = new PersistedBlockAccumulator(
                    rs.getString("template_slot_id"),
                    rs.getString("name"),
                    rs.getObject("start_time", OffsetDateTime.class),
                    rs.getObject("end_time", OffsetDateTime.class));
                accumulators.put(blockId, accumulator);
            }
            UUID memberId = rs.getObject("member_id", UUID.class);
            if (memberId != null) {
                accumulator.members().add(memberId);
            }
        }, userId, dayStart, dayEnd, notBefore);

        return accumulators.entrySet().stream()
            .map(entry -> new PlannerBlockIdentity.PersistedBlock(
                entry.getKey(), entry.getValue().templateSlotId(), entry.getValue().members(),
                entry.getValue().start(), entry.getValue().end(), entry.getValue().name()))
            .toList();
    }

    @Override
    public boolean upsertBlock(PlannerBlockRow block) {
        return jdbcTemplate.update(UPSERT_BLOCK_SQL,
            block.blockId(), block.userId(), block.name(), block.description(),
            block.start(), block.end(), block.templateSlotId()) > 0;
    }


    @Override
    public Optional<UUID> findCycleIdByName(UUID userId, String cycleName) {
        return jdbcTemplate.query(CYCLE_ID_BY_NAME_SQL,
            (rs, rowNum) -> rs.getObject("id", UUID.class), userId, cycleName)
            .stream().findFirst();
    }

    @Override
    public Optional<UUID> findActivityOverlapping(UUID userId, UUID cycleId, OffsetDateTime start,
                                                  OffsetDateTime end) {
        return jdbcTemplate.query(OVERLAPPING_ACTIVITY_SQL,
            (rs, rowNum) -> rs.getObject("id", UUID.class), userId, cycleId, end, start)
            .stream().findFirst();
    }

    @Override
    public void insertCompletedActivity(UUID activityId, UUID userId, UUID cycleId, String name,
                                        OffsetDateTime start, OffsetDateTime end) {
        jdbcTemplate.update(INSERT_COMPLETED_ACTIVITY_SQL,
            activityId, userId, cycleId, name, start, end);
    }

    @Override
    public boolean retimeActivityEnd(UUID activityId, OffsetDateTime end) {
        return jdbcTemplate.update(RETIME_ACTIVITY_END_SQL, end, activityId, end) > 0;
    }

    @Override
    public List<PlannedBlockRecord> loadPlannedBlocksForDay(UUID userId, LocalDate targetDay,
                                                            ZoneId zone) {
        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Map<UUID, PlannedBlockAccumulator> accumulators = new LinkedHashMap<>();
        jdbcTemplate.query(PLANNED_BLOCKS_FOR_DAY_SQL, rs -> {
            UUID blockId = rs.getObject("id", UUID.class);
            PlannedBlockAccumulator accumulator = accumulators.get(blockId);
            if (accumulator == null) {
                accumulator = new PlannedBlockAccumulator(
                    rs.getString("theme"),
                    rs.getObject("date_start", OffsetDateTime.class),
                    rs.getObject("date_end", OffsetDateTime.class),
                    rs.getString("reason"));
                accumulators.put(blockId, accumulator);
            }
            accumulator.members().add(new PlannedBlockMember(
                rs.getObject("member_id", UUID.class),
                rs.getString("member_name"),
                rs.getInt("member_minutes"),
                rs.getInt("member_ord")));
        }, userId, dayStart, dayEnd);

        return accumulators.entrySet().stream()
            .map(entry -> new PlannedBlockRecord(
                entry.getKey(), entry.getValue().theme(), entry.getValue().members(),
                entry.getValue().start(), entry.getValue().end(), entry.getValue().reason()))
            .toList();
    }

    @Override
    public java.util.Map<UUID, String> loadExecutableTitles(java.util.Collection<UUID> executableIds) {
        if (executableIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<UUID> ids = List.copyOf(executableIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = String.format(EXECUTABLE_TITLES_SQL, placeholders);
        java.util.Map<UUID, String> titles = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            titles.put(rs.getObject("id", UUID.class), rs.getString("name"));
        }, ids.toArray());
        return titles;
    }

    @Override
    public Map<UUID, List<UUID>> loadBlockMembers(java.util.Collection<UUID> blockIds) {
        if (blockIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(blockIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<UUID, List<UUID>> members = new LinkedHashMap<>();
        jdbcTemplate.query(String.format(BLOCK_MEMBERS_SQL, placeholders), rs -> {
            members.computeIfAbsent(rs.getObject("block_id", UUID.class), key -> new ArrayList<>())
                .add(rs.getObject("member_id", UUID.class));
        }, ids.toArray());
        // Immutable to the caller: this read model is shared by the floor and the LLM road within one
        // run, and neither of them owns it.
        members.replaceAll((blockId, blockMembers) -> List.copyOf(blockMembers));
        return Map.copyOf(members);
    }

    /** Groups the joined block/member rows of {@link #loadRegenerableBlocks} into one block. */
    private record PersistedBlockAccumulator(Set<UUID> members, String templateSlotId, String name,
                                             OffsetDateTime start, OffsetDateTime end) {

        PersistedBlockAccumulator(String templateSlotId, String name, OffsetDateTime start,
                                  OffsetDateTime end) {
            this(new LinkedHashSet<>(), templateSlotId, name, start, end);
        }
    }

    /** Groups the joined block/member rows of {@link #loadPlannedBlocksForDay} into one block. */
    private record PlannedBlockAccumulator(List<PlannedBlockMember> members, String theme,
                                           OffsetDateTime start, OffsetDateTime end, String reason) {

        PlannedBlockAccumulator(String theme, OffsetDateTime start, OffsetDateTime end,
                                String reason) {
            this(new ArrayList<>(), theme, start, end, reason);
        }
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
