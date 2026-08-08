package com.hyperbrain.core.infrastructure;

import com.hyperbrain.core.domain.model.BlockWindow;
import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.model.SubtaskCounts;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link ExecutableStateRepository}. Every write targets only the
 * SYSTEM-owned accounting columns (ADR-013/ADR-039) or columns the sync upsert never lists
 * ({@code last_completed_at}, containment, streaks), so these side writes and the ingestion
 * upsert compose without clobbering each other inside the same transaction.
 */
@Repository
class JdbcExecutableStateRepository implements ExecutableStateRepository {


    /**
     * A task is "actively focused" when an executing block accounts for it: a FOCUS child
     * ({@code parent_id}) or its current container, in the new TIME_BLOCK model — or a legacy
     * {@code core_time_block} ACTIVE row (frozen table; pre-migration data).
     */


    private static final String IS_SYSTEM_GENERATED_SQL =
        "SELECT system_generated FROM core_executable WHERE id = ?";

    private static final String COUNT_USER_SUBTASKS_SQL = """
        SELECT COUNT(*) AS total,
               COUNT(*) FILTER (WHERE status = 'DONE') AS done
        FROM core_executable
        WHERE parent_id = ? AND system_generated = false
          AND type <> 'TIME_BLOCK' AND id <> ?
        """;





    private static final String UPDATE_PROGRESS_SQL =
        "UPDATE core_executable SET progress = ? WHERE id = ?";

    private static final String MARK_COMPLETED_SQL =
        "UPDATE core_executable SET last_completed_at = ? WHERE id = ?";



    /**
     * DR-08 settlement sweep over the executable block model (ADR-039): user subtasks of the
     * block's FOCUS anchor or of any contained member, closed as DONE inside the window and
     * not yet imputed. FAILED closures earn no credit (status filter).
     */

    private static final String UPSERT_EXECUTABLE_SQL = """
        INSERT INTO core_executable
            (id, user_id, parent_id, cycle_id, name, description, type, status,
             priority_score, urgency_score, effort_score, is_important, frequency,
             start_time, end_time, source_calendar, container_block_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            parent_id          = EXCLUDED.parent_id,
            cycle_id           = EXCLUDED.cycle_id,
            name               = EXCLUDED.name,
            description        = EXCLUDED.description,
            type               = EXCLUDED.type,
            status             = EXCLUDED.status,
            priority_score     = EXCLUDED.priority_score,
            urgency_score      = EXCLUDED.urgency_score,
            effort_score       = EXCLUDED.effort_score,
            is_important       = EXCLUDED.is_important,
            frequency          = EXCLUDED.frequency,
            start_time         = EXCLUDED.start_time,
            end_time           = EXCLUDED.end_time,
            source_calendar    = EXCLUDED.source_calendar,
            container_block_id = EXCLUDED.container_block_id
        """;

    private static final String UPSERT_PROFILE_SQL = """
        INSERT INTO core_execution_profile (executable_id, energy_drain, mental_load, impact)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (executable_id) DO UPDATE SET
            energy_drain = EXCLUDED.energy_drain,
            mental_load  = EXCLUDED.mental_load,
            impact       = EXCLUDED.impact
        """;

    /**
     * The child's scheduling authority (ADR-039): the in-flight merged links win (container, then
     * parent), then the persisted ones (container, then parent). The persisted parent fallback is
     * what makes the pull symmetric with the push (COPY_SCHEDULE_SQL walks parent_id from the DB):
     * a subtask whose partial edit (or an Apple ingestion) carries no parent relation still resolves
     * its persisted parent and re-takes its date+cycle. On CREATE the child row does not exist yet,
     * so the merged links decide alone.
     */
    private static final String FIND_CONTAINER_SCHEDULE_SQL = """
        SELECT c.id, c.name, c.start_time, c.end_time, c.cycle_id
        FROM core_executable c
        WHERE c.id = COALESCE(
            ?::uuid,
            ?::uuid,
            (SELECT child.container_block_id FROM core_executable child WHERE child.id = ?),
            (SELECT child.parent_id FROM core_executable child WHERE child.id = ?))
        """;

    /**
     * The transitive containment closure of one container (members via {@code
     * container_block_id}, subtasks via {@code parent_id}), excluding {@code TIME_BLOCK} rows
     * (containers own their window) and system snapshots (frozen history). The walk carries a
     * path guard and a defensive depth bound like every other recursive read of this codebase.
     * The single UPDATE applies the DR-01 projection per child type and only touches rows whose
     * values actually differ ({@code IS DISTINCT FROM} guards) — the idempotence that keeps
     * echoes event-free. {@code RETURNING} reports the changed ids for the outbox fan-out.
     */
    private static final String COPY_SCHEDULE_SQL = """
        WITH RECURSIVE contained (id, type, depth, path) AS (
            SELECT e.id, e.type, 1, ARRAY[e.id]
            FROM core_executable e
            WHERE (e.parent_id = ? OR e.container_block_id = ?)
              AND e.type <> 'TIME_BLOCK'
              AND e.system_generated = false
            UNION ALL
            SELECT child.id, child.type, c.depth + 1, c.path || child.id
            FROM contained c
            JOIN core_executable child
              ON (child.parent_id = c.id OR child.container_block_id = c.id)
            WHERE c.depth < 8
              AND NOT child.id = ANY(c.path)
              AND child.type <> 'TIME_BLOCK'
              AND child.system_generated = false
        )
        UPDATE core_executable e
        SET start_time = ?::timestamptz,
            end_time   = CASE WHEN e.type IN ('ACTIVITY', 'AGENDA', 'LEARNING_SESSION')
                              THEN ?::timestamptz ELSE NULL END,
            cycle_id   = COALESCE(?::uuid, e.cycle_id)
        FROM (SELECT DISTINCT id, type FROM contained) c
        WHERE e.id = c.id
          AND (e.start_time IS DISTINCT FROM ?::timestamptz
               OR e.end_time IS DISTINCT FROM
                  CASE WHEN e.type IN ('ACTIVITY', 'AGENDA', 'LEARNING_SESSION')
                       THEN ?::timestamptz ELSE NULL END
               OR e.cycle_id IS DISTINCT FROM COALESCE(?::uuid, e.cycle_id))
        RETURNING e.id
        """;

    private static final String ASSIGN_CONTAINER_SQL = """
        UPDATE core_executable
        SET container_block_id = ?, container_planned_minutes = ?, container_ord = ?
        WHERE id = ?
          AND (container_block_id IS DISTINCT FROM ?
               OR container_planned_minutes IS DISTINCT FROM ?
               OR container_ord IS DISTINCT FROM ?)
        """;

    /**
     * The hand-over of a block the user rearranged by hand. Guarded to the regenerable set inside the
     * predicate, so it is race-safe and a second observation of the same edit writes nothing: once the
     * block is his, it is no longer {@code PLANNER}.
     */
    private static final String CLAIM_BLOCK_FOR_USER_SQL = """
        UPDATE core_executable
        SET origin = 'USER'
        WHERE id     = ?
          AND type   = 'TIME_BLOCK'
          AND origin = 'PLANNER'
          AND status = 'PLANNED'
        """;

    private static final String CLEAR_CONTAINER_SQL = """
        UPDATE core_executable
        SET container_block_id = NULL, container_planned_minutes = NULL, container_ord = NULL
        WHERE id = ? AND container_block_id IS NOT NULL
        """;

    private static final String ACHIEVED_STREAK_SQL = """
        UPDATE core_executable
        SET current_streak = current_streak + 1,
            best_streak    = GREATEST(best_streak, current_streak + 1)
        WHERE id = ?
        """;

    private static final String RESET_STREAK_SQL =
        "UPDATE core_executable SET current_streak = 0 WHERE id = ?";

    private static final String COPY_STREAKS_SQL = """
        UPDATE core_executable target
        SET current_streak = source.current_streak,
            best_streak    = source.best_streak
        FROM core_executable source
        WHERE target.id = ? AND source.id = ?
        """;

    /**
     * ADR-040 D4 candidates: still-open, user-owned rows of a swept type whose window closed on a
     * local day before the reference day. {@code COALESCE(end_time, start_time)} is the window's
     * closing instant — a reminder type has no {@code end_time} (DR-01), so its due instant is
     * both ends of its window. The type list is bound as a parameter so it can only come from
     * {@code OverduePolicy}. Row-locked with {@code SKIP LOCKED} like every other sweep of this
     * codebase, so a second instance never fights this one for the same row.
     */
    private static final String FIND_OVERDUE_SQL = """
        SELECT e.id, e.user_id, e.parent_id, e.cycle_id, e.name, e.description, e.type, e.status,
               e.priority_score, e.urgency_score, e.effort_score, e.is_important, e.frequency,
               e.start_time, e.end_time, e.source_calendar, e.system_generated, e.container_block_id,
               p.energy_drain, p.mental_load, p.impact
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        WHERE e.user_id = ?
          AND e.status NOT IN ('DONE', 'FAILED')
          AND e.system_generated = false
          AND e.parent_id IS NULL
          AND e.type = ANY (string_to_array(?, ','))
          AND e.start_time IS NOT NULL
          AND COALESCE(
                  e.end_time,
                  CASE WHEN (e.start_time AT TIME ZONE ?) = date_trunc('day', e.start_time AT TIME ZONE ?)
                       THEN ((date_trunc('day', e.start_time AT TIME ZONE ?) + interval '1 day')
                             AT TIME ZONE ?)
                       ELSE e.start_time
                  END
              ) < ?
        ORDER BY COALESCE(e.end_time, e.start_time), e.id
        FOR UPDATE OF e SKIP LOCKED
        """;

    /** The snapshot projection {@link #SNAPSHOT_MAPPER} reads, shared by every snapshot query. */
    private static final String SNAPSHOT_COLUMNS = """
        SELECT e.id, e.user_id, e.parent_id, e.cycle_id, e.name, e.description, e.type, e.status,
               e.priority_score, e.urgency_score, e.effort_score, e.is_important, e.frequency,
               e.start_time, e.end_time, e.source_calendar, e.system_generated, e.container_block_id,
               p.energy_drain, p.mental_load, p.impact
        FROM core_executable e
        LEFT JOIN core_execution_profile p ON p.executable_id = e.id
        """;

    /** Ids are bound as one comma-joined parameter, the same shape the overdue sweep already uses. */
    private static final String FIND_ALL_BY_ID_SQL =
        SNAPSHOT_COLUMNS + "WHERE e.id = ANY (string_to_array(?, ',')::uuid[])";

    private static final String FIND_CONTAINED_BY_SQL =
        SNAPSHOT_COLUMNS + """
        WHERE e.container_block_id = ?
        ORDER BY e.container_ord NULLS LAST, e.id
        """;

    /**
     * ADR-040 D10 — the withdrawal of a planner block, with every guard in the predicate: planner
     * authorship, still planned, no frozen duration, no completion clock, no member still contained and
     * nothing imputed to it. The two {@code NOT EXISTS} are what make "let go first, delete after" an
     * enforced order rather than a convention.
     */
    private static final String DELETE_WITHDRAWN_BLOCK_SQL = """
        DELETE FROM core_executable b
        WHERE b.id     = ?
          AND b.type   = 'TIME_BLOCK'
          AND b.origin = 'PLANNER'
          AND b.status = 'PLANNED'
          AND b.actual_duration_minutes IS NULL
          AND b.last_completed_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM core_executable m WHERE m.container_block_id = b.id)
          AND NOT EXISTS (SELECT 1 FROM core_executable i WHERE i.imputed_block_id   = b.id)
        """;

    private static final String FIND_BLOCK_WINDOW_SQL = """
        SELECT id, user_id, name, description, start_time, end_time, template_slot_id
        FROM core_executable
        WHERE id = ? AND type = 'TIME_BLOCK'
        """;

    /**
     * The block holding a given template band on a given local day. Scoped to planner-authored,
     * still-open blocks: a closed one is history, and a USER block is the user's own arrangement, which
     * a clone must not silently join.
     */
    private static final String FIND_BLOCK_ON_DAY_BY_SLOT_SQL = """
        SELECT id
        FROM core_executable
        WHERE user_id = ?
          AND type = 'TIME_BLOCK'
          AND origin = 'PLANNER'
          AND status = 'PLANNED'
          AND template_slot_id = ?
          AND start_time >= ?
          AND start_time <  ?
        ORDER BY start_time
        LIMIT 1
        """;

    private static final String INSERT_BLOCK_SQL = """
        INSERT INTO core_executable
            (id, user_id, name, description, type, status, origin,
             start_time, end_time, template_slot_id, system_generated)
        VALUES (?, ?, ?, ?, 'TIME_BLOCK', 'PLANNED', 'PLANNER', ?, ?, ?, false)
        """;

    private static final String FIND_USER_ZONE_SQL = "SELECT timezone FROM sys_user WHERE id = ?";

    private static final RowMapper<BlockWindow> BLOCK_WINDOW_MAPPER = (rs, rowNum) -> new BlockWindow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        toOffset(rs.getTimestamp("start_time")),
        toOffset(rs.getTimestamp("end_time")),
        rs.getString("template_slot_id"));

    private static final String CLOSE_AS_FAILED_SQL = """
        UPDATE core_executable
        SET status = 'FAILED'
        WHERE id = ? AND status NOT IN ('DONE', 'FAILED')
        """;

    private static final String RESCHEDULE_SQL = """
        UPDATE core_executable
        SET start_time = ?, end_time = ?
        WHERE id = ?
          AND status NOT IN ('DONE', 'FAILED')
          AND (start_time IS DISTINCT FROM ? OR end_time IS DISTINCT FROM ?)
        """;

    private static final RowMapper<ExecutableSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) ->
        new ExecutableSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            rs.getObject("cycle_id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("type"),
            rs.getString("status"),
            rs.getObject("priority_score", Double.class),
            rs.getObject("urgency_score", Double.class),
            rs.getObject("effort_score", Double.class),
            rs.getBoolean("is_important"),
            rs.getObject("frequency", Double.class),
            toOffset(rs.getTimestamp("start_time")),
            toOffset(rs.getTimestamp("end_time")),
            rs.getString("source_calendar"),
            rs.getObject("energy_drain", Integer.class),
            rs.getObject("mental_load", Integer.class),
            rs.getObject("impact", Integer.class),
            rs.getBoolean("system_generated"),
            rs.getObject("container_block_id", UUID.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcExecutableStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    public void updateProgress(UUID executableId, Double progress) {
        jdbcTemplate.update(UPDATE_PROGRESS_SQL, progress, executableId);
    }

    @Override
    public void markCompleted(UUID executableId, OffsetDateTime completedAt) {
        jdbcTemplate.update(MARK_COMPLETED_SQL, toTimestamp(completedAt), executableId);
    }




    @Override
    public void upsertExecutable(ExecutableSnapshot s) {
        jdbcTemplate.update(UPSERT_EXECUTABLE_SQL,
            s.id(), s.userId(), s.parentId(), s.cycleId(), s.name(), s.description(),
            s.type(), s.status(), s.priorityScore(), s.urgencyScore(), s.effortScore(),
            Boolean.TRUE.equals(s.isImportant()), s.frequency(),
            toTimestamp(s.startTime()), toTimestamp(s.endTime()), s.sourceCalendar(),
            s.containerBlockId());
        jdbcTemplate.update(UPSERT_PROFILE_SQL,
            s.id(), s.energyDrain(), s.mentalLoad(), s.impact());
    }

    @Override
    public Optional<ContainerSchedule> findContainerSchedule(UUID executableId,
                                                             UUID mergedContainerId,
                                                             UUID mergedParentId) {
        List<ContainerSchedule> rows = jdbcTemplate.query(FIND_CONTAINER_SCHEDULE_SQL,
            (rs, rowNum) -> new ContainerSchedule(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                toOffset(rs.getTimestamp("start_time")),
                toOffset(rs.getTimestamp("end_time")),
                rs.getObject("cycle_id", UUID.class)),
            mergedContainerId, mergedParentId, executableId, executableId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<UUID> copyScheduleToContained(UUID containerId, OffsetDateTime start,
                                              OffsetDateTime end, UUID cycleId) {
        Timestamp startTs = toTimestamp(start);
        Timestamp endTs = toTimestamp(end);
        // UPDATE ... RETURNING runs as a query on the PostgreSQL driver.
        return jdbcTemplate.query(COPY_SCHEDULE_SQL,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            containerId, containerId,
            startTs, endTs, cycleId,
            startTs, endTs, cycleId);
    }

    @Override
    public boolean assignContainer(UUID memberId, UUID blockId, Integer plannedMinutes, int ord) {
        return jdbcTemplate.update(ASSIGN_CONTAINER_SQL,
            blockId, plannedMinutes, ord, memberId, blockId, plannedMinutes, ord) > 0;
    }

    @Override
    public boolean claimBlockForUser(UUID blockId) {
        return jdbcTemplate.update(CLAIM_BLOCK_FOR_USER_SQL, blockId) > 0;
    }

    @Override
    public boolean clearContainer(UUID memberId) {
        return jdbcTemplate.update(CLEAR_CONTAINER_SQL, memberId) > 0;
    }

    @Override
    public void recordAchievedStreak(UUID executableId) {
        jdbcTemplate.update(ACHIEVED_STREAK_SQL, executableId);
    }

    @Override
    public void resetStreak(UUID executableId) {
        jdbcTemplate.update(RESET_STREAK_SQL, executableId);
    }

    @Override
    public void copyStreaks(UUID sourceId, UUID targetId) {
        jdbcTemplate.update(COPY_STREAKS_SQL, targetId, sourceId);
    }

    @Override
    public List<ExecutableSnapshot> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String joined = ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
        return jdbcTemplate.query(FIND_ALL_BY_ID_SQL, SNAPSHOT_MAPPER, joined);
    }

    @Override
    public List<ExecutableSnapshot> findContainedBy(UUID blockId) {
        return jdbcTemplate.query(FIND_CONTAINED_BY_SQL, SNAPSHOT_MAPPER, blockId);
    }

    @Override
    public Optional<BlockWindow> findBlockWindow(UUID blockId) {
        return jdbcTemplate.query(FIND_BLOCK_WINDOW_SQL, BLOCK_WINDOW_MAPPER, blockId)
            .stream().findFirst();
    }

    @Override
    public Optional<UUID> findBlockOnDayBySlot(UUID userId, String templateSlotId,
                                               OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        return jdbcTemplate.queryForList(FIND_BLOCK_ON_DAY_BY_SLOT_SQL, UUID.class,
            userId, templateSlotId, toTimestamp(dayStart), toTimestamp(dayEnd)).stream().findFirst();
    }

    @Override
    public void insertBlock(BlockWindow block) {
        jdbcTemplate.update(INSERT_BLOCK_SQL,
            block.blockId(), block.userId(), block.name(), block.description(),
            toTimestamp(block.start()), toTimestamp(block.end()), block.templateSlotId());
    }

    @Override
    public ZoneId findUserZone(UUID userId) {
        return ZoneId.of(jdbcTemplate.queryForObject(FIND_USER_ZONE_SQL, String.class, userId));
    }

    @Override
    public boolean deleteWithdrawnBlock(UUID blockId) {
        return jdbcTemplate.update(DELETE_WITHDRAWN_BLOCK_SQL, blockId) > 0;
    }

    @Override
    public List<ExecutableSnapshot> findOverdue(UUID userId, Collection<String> types,
                                                OffsetDateTime referenceInstant, ZoneId zone) {
        if (types.isEmpty()) {
            return List.of();
        }
        String zoneId = zone.getId();
        return jdbcTemplate.query(FIND_OVERDUE_SQL, SNAPSHOT_MAPPER,
            userId, String.join(",", types), zoneId, zoneId, zoneId, zoneId,
            toTimestamp(referenceInstant));
    }

    @Override
    public boolean closeAsFailed(UUID executableId) {
        return jdbcTemplate.update(CLOSE_AS_FAILED_SQL, executableId) > 0;
    }

    @Override
    public boolean reschedule(UUID executableId, OffsetDateTime startTime, OffsetDateTime endTime) {
        Timestamp start = toTimestamp(startTime);
        Timestamp end = toTimestamp(endTime);
        return jdbcTemplate.update(RESCHEDULE_SQL, start, end, executableId, start, end) > 0;
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
