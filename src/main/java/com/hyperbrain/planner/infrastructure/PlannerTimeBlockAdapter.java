package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.sync.domain.model.TimeBlockEditOutcome;
import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockUserEdit;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * JDBC adapter for {@link PlannerTimeBlockPort} (ADR-038): the planner-owned application of
 * inbound Notion time-block edits. Mirrors the {@link PlannerBlockDeletionAdapter} seam — the
 * compile dependency runs {@code planner → sync}, never the reverse, and every block invariant
 * is enforced here, where the aggregate lives:
 *
 * <ul>
 *   <li><b>D5 one-live-block-per-executable-per-day</b> — an added member already held by a live
 *       {@code PLANNED} block that day is <em>moved</em> deterministically: a pre-check SELECT on
 *       the {@code uq_core_time_block_member_executable_day} index domain, then DELETE from the
 *       source block <em>before</em> the INSERT into the target, same transaction; the unique
 *       index itself remains the TOCTOU net (a concurrent 23505 becomes a rejection, never a
 *       poisoned ingestion).</li>
 *   <li><b>WIG atomicity (ADR-027 D4)</b> — a block holding a lead measure neither gains nor
 *       loses companions, a lead measure never joins a themed block, and a member is never pulled
 *       out of a WIG block.</li>
 *   <li><b>Planner walls</b> — a retimed or created window may not overlap a read-only AGENDA
 *       executable (ADR-009) nor another live block of the user (the planner's block-vs-block
 *       non-overlap), the persisted subset of the {@code ProposalWallGuard} walls.</li>
 *   <li><b>Non-empty invariant</b> — a move that empties a live source block deletes it
 *       ({@code PersistedBlock} rejects empty membership); an edit that would empty the edited
 *       block itself is rejected instead.</li>
 * </ul>
 *
 * <p>An applied edit flips {@code origin = 'USER'}, the wall the daily regeneration
 * ({@code reconcilePlannedBlocks}, scoped to {@code origin = 'PLANNER'}) already respects, so a
 * user's Notion edit survives every replan. Rejections are data, never exceptions: a permanent
 * semantic conflict must re-assert canonical state, not loop through the DLQ.
 */
@Repository
@Transactional
class PlannerTimeBlockAdapter implements PlannerTimeBlockPort {

    private static final Logger log = LoggerFactory.getLogger(PlannerTimeBlockAdapter.class);

    private static final String LEAD_MEASURE = "LEAD_MEASURE";

    private static final String BLOCK_COLUMNS = """
        SELECT b.id, e.user_id, b.date_start, b.date_end, b.status, b.origin, b.theme, b.reason,
               b.planned_minutes, b.actual_duration_minutes, b.settled_at,
               COALESCE(m.executable_id, b.executable_id) AS member_id,
               COALESCE(me.name, e.name)                  AS member_name,
               COALESCE(m.planned_minutes, b.planned_minutes, 0) AS member_minutes,
               COALESCE(m.ord, 0)                         AS member_ord
        FROM core_time_block b
        JOIN core_executable e ON e.id = b.executable_id
        LEFT JOIN core_time_block_member m ON m.block_id = b.id
        LEFT JOIN core_executable me ON me.id = m.executable_id
        """;

    private static final String FIND_BLOCK_SQL = BLOCK_COLUMNS + """
        WHERE b.id = ?
        ORDER BY member_ord
        """;

    private static final String LIVE_BLOCKS_FOR_DAY_SQL = BLOCK_COLUMNS + """
        WHERE e.user_id = ?
          AND b.status IN ('PLANNED', 'ACTIVE')
          AND b.origin IN ('PLANNER', 'USER')
          AND b.date_start >= ?
          AND b.date_start < ?
        ORDER BY b.date_start, b.id, member_ord
        """;

    private static final String MIRRORABLE_BLOCKS_SQL = BLOCK_COLUMNS + """
        WHERE b.origin IN ('PLANNER', 'USER')
          AND (b.status IN ('PLANNED', 'ACTIVE')
               OR (b.status = 'SETTLED' AND b.settled_at >= ?))
        ORDER BY b.date_start, b.id, member_ord
        """;

    private static final String TERMINAL_BLOCKS_BEFORE_SQL = """
        SELECT b.id
        FROM core_time_block b
        WHERE b.status IN ('SETTLED', 'EXPIRED')
          AND b.settled_at IS NOT NULL
          AND b.settled_at < ?
        ORDER BY b.settled_at
        """;

    /** The user's read-only AGENDA windows overlapping the candidate window (ADR-009 wall). */
    private static final String AGENDA_WALL_OVERLAP_SQL = """
        SELECT EXISTS (
            SELECT 1 FROM core_executable e
            WHERE e.user_id = ?
              AND e.type = 'AGENDA'
              AND e.start_time IS NOT NULL AND e.end_time IS NOT NULL
              AND e.start_time < ? AND e.end_time > ?
        )
        """;

    /** Another live block of the user overlapping the candidate window (block-vs-block wall). */
    private static final String BLOCK_WALL_OVERLAP_SQL = """
        SELECT EXISTS (
            SELECT 1 FROM core_time_block b
            JOIN core_executable e ON e.id = b.executable_id
            WHERE e.user_id = ?
              AND b.id <> ?
              AND b.status IN ('PLANNED', 'ACTIVE')
              AND b.date_start < ?
              AND COALESCE(b.date_end, b.date_start + interval '1 minute') > ?
        )
        """;

    /**
     * D5 pre-check: where (if anywhere) an executable is already held that day. Scans exactly
     * the domain of {@code uq_core_time_block_member_executable_day} (one index scan).
     */
    private static final String HELD_THAT_DAY_SQL = """
        SELECT m.block_id, m.block_status
        FROM core_time_block_member m
        WHERE m.executable_id = ?
          AND m.block_date = ?
          AND m.block_status IN ('PLANNED', 'ACTIVE', 'SETTLED')
        """;

    private static final String BLOCK_HAS_LEAD_MEMBER_SQL = """
        SELECT EXISTS (
            SELECT 1 FROM core_time_block_member m
            JOIN core_executable e ON e.id = m.executable_id
            WHERE m.block_id = ? AND e.type = 'LEAD_MEASURE'
        )
        """;

    private static final String EXECUTABLE_TYPE_SQL =
        "SELECT type FROM core_executable WHERE id = ?";

    private static final String USER_ZONE_SQL =
        "SELECT timezone FROM sys_user WHERE id = ?";

    private static final String DELETE_MEMBER_SQL =
        "DELETE FROM core_time_block_member WHERE block_id = ? AND executable_id = ?";

    private static final String COUNT_MEMBERS_SQL =
        "SELECT count(*) FROM core_time_block_member WHERE block_id = ?";

    private static final String MAX_ORD_SQL =
        "SELECT COALESCE(max(ord), -1) FROM core_time_block_member WHERE block_id = ?";

    private static final String INSERT_MEMBER_SQL = """
        INSERT INTO core_time_block_member (block_id, executable_id, planned_minutes, ord)
        VALUES (?, ?, ?, ?)
        """;

    private static final String DELETE_LIVE_BLOCK_SQL = """
        DELETE FROM core_time_block
        WHERE id = ?
          AND status = 'PLANNED'
          AND origin IN ('PLANNER', 'USER')
        """;

    private static final String RETIME_BLOCK_SQL = """
        UPDATE core_time_block
        SET date_start = ?, date_end = ?, planned_minutes = ?, origin = 'USER'
        WHERE id = ? AND status = 'PLANNED'
        """;

    private static final String RETHEME_BLOCK_SQL = """
        UPDATE core_time_block
        SET theme = ?, origin = 'USER'
        WHERE id = ? AND status = 'PLANNED'
        """;

    private static final String FLIP_ORIGIN_SQL = """
        UPDATE core_time_block
        SET origin = 'USER'
        WHERE id = ? AND status = 'PLANNED'
        """;

    private static final String REANCHOR_BLOCK_SQL = """
        UPDATE core_time_block
        SET executable_id = ?
        WHERE id = ? AND status = 'PLANNED'
        """;

    private static final String INSERT_USER_BLOCK_SQL = """
        INSERT INTO core_time_block
            (id, executable_id, date_start, date_end, status, origin, planned_minutes, theme)
        VALUES (?, ?, ?, ?, 'PLANNED', 'USER', ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    PlannerTimeBlockAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TimeBlockSnapshot> findBlock(UUID blockId) {
        List<TimeBlockSnapshot> blocks = queryBlocks(FIND_BLOCK_SQL, blockId);
        return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeBlockSnapshot> findLiveBlocksForDay(UUID userId, OffsetDateTime dayStart,
                                                        OffsetDateTime dayEnd) {
        return queryBlocks(LIVE_BLOCKS_FOR_DAY_SQL, userId, toTimestamp(dayStart), toTimestamp(dayEnd));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeBlockSnapshot> findMirrorableBlocks(OffsetDateTime settledAfter) {
        return queryBlocks(MIRRORABLE_BLOCKS_SQL, toTimestamp(settledAfter));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findTerminalBlockIdsSettledBefore(OffsetDateTime settledBefore) {
        return jdbcTemplate.queryForList(TERMINAL_BLOCKS_BEFORE_SQL, UUID.class,
            toTimestamp(settledBefore));
    }

    @Override
    public TimeBlockEditOutcome applyUserEdit(TimeBlockUserEdit edit) {
        Optional<TimeBlockSnapshot> current = findBlock(edit.blockId());
        if (current.isEmpty()) {
            return TimeBlockEditOutcome.noop();
        }
        TimeBlockSnapshot block = current.get();
        if (!"PLANNED".equals(block.status()) || "FOCUS".equals(block.origin())) {
            return new TimeBlockEditOutcome(false,
                List.of("Block is not editable (status " + block.status() + ")"),
                Set.of(), Set.of(), Set.of(), Set.of());
        }

        List<String> rejections = new ArrayList<>();
        Set<UUID> added = new LinkedHashSet<>();
        Set<UUID> removed = new LinkedHashSet<>();
        Set<UUID> remirror = new LinkedHashSet<>();
        Set<UUID> deleted = new LinkedHashSet<>();
        boolean applied = false;

        boolean retimed = edit.retimes() && applyRetime(block, edit, rejections);
        applied |= retimed;
        // The membership day must follow the window actually persisted, not a rejected retime.
        OffsetDateTime effectiveStart = retimed ? edit.newStart() : block.dateStart();
        OffsetDateTime effectiveEnd = retimed ? edit.newEnd() : block.dateEnd();
        if (edit.rethemes()) {
            jdbcTemplate.update(RETHEME_BLOCK_SQL, edit.newTheme(), block.id());
            applied = true;
        }
        if (edit.changesMembership()) {
            applied |= applyMembershipDiff(block, edit.desiredMembers(), effectiveStart,
                effectiveEnd, rejections, added, removed, remirror, deleted);
        }
        if (applied) {
            // Idempotent when the retime/retheme already flipped it; covers the membership-only edit.
            jdbcTemplate.update(FLIP_ORIGIN_SQL, block.id());
        }
        return new TimeBlockEditOutcome(applied, rejections, added, removed, remirror, deleted);
    }

    @Override
    public TimeBlockEditOutcome createUserBlock(UUID userId, UUID blockId, OffsetDateTime start,
                                                OffsetDateTime end, String theme,
                                                List<UUID> members) {
        List<String> rejections = new ArrayList<>();
        if (violatesWalls(userId, blockId, start, end, rejections)) {
            return new TimeBlockEditOutcome(false, rejections, Set.of(), Set.of(), Set.of(), Set.of());
        }
        LocalDate blockDate = localDay(userId, start);
        Set<UUID> added = new LinkedHashSet<>();
        Set<UUID> remirror = new LinkedHashSet<>();
        Set<UUID> deleted = new LinkedHashSet<>();

        List<UUID> survivors = filterWigCompanions(members, rejections);
        List<UUID> accepted = new ArrayList<>();
        for (UUID member : survivors) {
            if (resolveDayConflict(member, blockDate, null, rejections, remirror, deleted)) {
                accepted.add(member);
            }
        }
        if (accepted.isEmpty()) {
            rejections.add("A block needs at least one schedulable task");
            return new TimeBlockEditOutcome(false, rejections, Set.of(), Set.of(), remirror, deleted);
        }

        int durationMinutes = (int) Duration.between(start, end).toMinutes();
        jdbcTemplate.update(INSERT_USER_BLOCK_SQL, blockId, accepted.get(0),
            toTimestamp(start), toTimestamp(end), durationMinutes, theme);
        int share = Math.max(durationMinutes / accepted.size(), 0);
        for (int ord = 0; ord < accepted.size(); ord++) {
            if (insertMemberCatchingRace(blockId, accepted.get(ord), share, ord, rejections)) {
                added.add(accepted.get(ord));
            }
        }
        if (added.isEmpty()) {
            // Every insert lost its TOCTOU race: an anchored block without members would be an
            // invisible orphan (PersistedBlock non-empty invariant), so undo the creation.
            jdbcTemplate.update(DELETE_LIVE_BLOCK_SQL, blockId);
            return new TimeBlockEditOutcome(false, rejections, Set.of(), Set.of(), remirror, deleted);
        }
        log.info("Notion-created USER block {} persisted with {} member(s)", blockId, added.size());
        return new TimeBlockEditOutcome(true, rejections, added, Set.of(), remirror, deleted);
    }

    @Override
    public boolean deleteLiveBlock(UUID blockId) {
        return jdbcTemplate.update(DELETE_LIVE_BLOCK_SQL, blockId) > 0;
    }

    // ── Edit facets ───────────────────────────────────────────────────────────

    /**
     * Applies the window change unless it violates a wall or would collide the members with
     * another day's live blocks (the D5 index guards the trigger-derived {@code block_date}
     * update; a violation is a rejection, not a poisoned ingestion).
     */
    private boolean applyRetime(TimeBlockSnapshot block, TimeBlockUserEdit edit,
                                List<String> rejections) {
        if (violatesWalls(block.userId(), block.id(), edit.newStart(), edit.newEnd(), rejections)) {
            return false;
        }
        int durationMinutes = (int) Duration.between(edit.newStart(), edit.newEnd()).toMinutes();
        try {
            jdbcTemplate.update(RETIME_BLOCK_SQL, toTimestamp(edit.newStart()),
                toTimestamp(edit.newEnd()), durationMinutes, block.id());
            return true;
        } catch (DataIntegrityViolationException ex) {
            rejections.add("Retime rejected: a member is already scheduled on the target day");
            log.warn("Retime of block {} tripped the D5 index; rejected", block.id());
            return false;
        }
    }

    private boolean applyMembershipDiff(TimeBlockSnapshot block, Set<UUID> desired,
                                        OffsetDateTime effectiveStart, OffsetDateTime effectiveEnd,
                                        List<String> rejections, Set<UUID> added, Set<UUID> removed,
                                        Set<UUID> remirror, Set<UUID> deleted) {
        Set<UUID> current = new LinkedHashSet<>();
        Map<UUID, TimeBlockMemberSnapshot> currentById = new LinkedHashMap<>();
        for (TimeBlockMemberSnapshot member : block.members()) {
            current.add(member.executableId());
            currentById.put(member.executableId(), member);
        }
        Set<UUID> toAdd = new LinkedHashSet<>(desired);
        toAdd.removeAll(current);
        Set<UUID> toRemove = new LinkedHashSet<>(current);
        toRemove.removeAll(desired);
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return false;
        }
        if (current.size() - toRemove.size() + toAdd.size() == 0) {
            rejections.add("Membership rejected: a block needs at least one task");
            return false;
        }
        if (isWigAtomic(block.id())) {
            rejections.add("Membership rejected: a WIG block is atomic (ADR-027 D4)");
            return false;
        }

        boolean changed = false;
        for (UUID executableId : toRemove) {
            if (jdbcTemplate.update(DELETE_MEMBER_SQL, block.id(), executableId) > 0) {
                removed.add(executableId);
                changed = true;
            }
        }
        LocalDate blockDate = localDay(block.userId(), effectiveStart);
        List<UUID> acceptedAdds = new ArrayList<>();
        for (UUID executableId : toAdd) {
            if (LEAD_MEASURE.equals(executableType(executableId))) {
                rejections.add("Add rejected: a lead measure rides alone (WIG atomicity)");
                continue;
            }
            if (resolveDayConflict(executableId, blockDate, block.id(), rejections, remirror,
                deleted)) {
                acceptedAdds.add(executableId);
            }
        }
        if (!acceptedAdds.isEmpty()) {
            int survivorsMinutes = current.stream()
                .filter(id -> !toRemove.contains(id))
                .mapToInt(id -> currentById.get(id).plannedMinutes())
                .sum();
            int blockMinutes = effectiveEnd != null
                ? (int) Duration.between(effectiveStart, effectiveEnd).toMinutes()
                : 0;
            int share = Math.max((blockMinutes - survivorsMinutes) / acceptedAdds.size(), 0);
            int nextOrd = jdbcTemplate.queryForObject(MAX_ORD_SQL, Integer.class, block.id()) + 1;
            for (UUID executableId : acceptedAdds) {
                if (insertMemberCatchingRace(block.id(), executableId, share, nextOrd++, rejections)) {
                    added.add(executableId);
                    changed = true;
                }
            }
        }
        if (changed) {
            reanchorIfNeeded(block, removed);
        }
        return changed;
    }

    /**
     * Resolves the D5 same-day conflict of one incoming member: not held → free to add; held by
     * a live {@code PLANNED} non-WIG block → deterministic MOVE (delete-before-insert, the
     * emptied source deleted, the surviving source re-mirrored); held by {@code ACTIVE}/
     * {@code SETTLED} work or a WIG block → rejected.
     *
     * @return true when the member may be inserted into the target block
     */
    private boolean resolveDayConflict(UUID executableId, LocalDate blockDate, UUID targetBlockId,
                                       List<String> rejections, Set<UUID> remirror,
                                       Set<UUID> deleted) {
        List<Map<String, Object>> held = jdbcTemplate.queryForList(HELD_THAT_DAY_SQL,
            executableId, java.sql.Date.valueOf(blockDate));
        if (held.isEmpty()) {
            return true;
        }
        UUID sourceBlockId = (UUID) held.get(0).get("block_id");
        String sourceStatus = (String) held.get(0).get("block_status");
        if (sourceBlockId.equals(targetBlockId)) {
            return true;
        }
        if (!"PLANNED".equals(sourceStatus)) {
            rejections.add("Add rejected: task already has " + sourceStatus.toLowerCase(java.util.Locale.ROOT)
                + " work that day");
            return false;
        }
        if (isWigAtomic(sourceBlockId)) {
            rejections.add("Add rejected: task belongs to an atomic WIG block (ADR-027 D4)");
            return false;
        }
        // Deterministic MOVE (D5): the departing membership must be gone before the new insert.
        jdbcTemplate.update(DELETE_MEMBER_SQL, sourceBlockId, executableId);
        Integer remaining = jdbcTemplate.queryForObject(COUNT_MEMBERS_SQL, Integer.class, sourceBlockId);
        if (remaining != null && remaining == 0) {
            // An emptied live block is an orphan invisible to the D3 reconciliation
            // (PersistedBlock rejects empty membership): delete it and let the sync layer
            // propagate the removal to both satellites.
            if (jdbcTemplate.update(DELETE_LIVE_BLOCK_SQL, sourceBlockId) > 0) {
                deleted.add(sourceBlockId);
                remirror.remove(sourceBlockId);
            }
        } else {
            findBlock(sourceBlockId).ifPresent(source -> reanchorIfNeeded(source, Set.of(executableId)));
            remirror.add(sourceBlockId);
        }
        log.info("D5 move: executable {} left block {} for a Notion-edited block", executableId,
            sourceBlockId);
        return true;
    }

    /**
     * Inserts one member, catching the unique-index race (23505) the pre-check cannot close
     * (TOCTOU): a concurrent writer that claimed the executable between the SELECT and this
     * INSERT turns the add into a rejection instead of aborting the ingestion transaction's
     * batch semantics with an unhandled constraint violation.
     */
    private boolean insertMemberCatchingRace(UUID blockId, UUID executableId, int plannedMinutes,
                                             int ord, List<String> rejections) {
        try {
            jdbcTemplate.update(INSERT_MEMBER_SQL, blockId, executableId, plannedMinutes, ord);
            return true;
        } catch (DuplicateKeyException ex) {
            rejections.add("Add rejected: task was claimed by another block concurrently");
            log.warn("D5 TOCTOU race on member {} of block {}; add rejected", executableId, blockId);
            return false;
        }
    }

    /**
     * Re-anchors {@code core_time_block.executable_id} onto the lowest-{@code ord} surviving
     * member when the current anchor left the block — the anchor column is NOT NULL and drives
     * the reads' anchor fallback.
     */
    private void reanchorIfNeeded(TimeBlockSnapshot block, Set<UUID> removedNow) {
        UUID anchor = block.members().isEmpty() ? null : block.members().get(0).executableId();
        if (anchor == null || !removedNow.contains(anchor)) {
            return;
        }
        block.members().stream()
            .filter(member -> !removedNow.contains(member.executableId()))
            .min(Comparator.comparingInt(TimeBlockMemberSnapshot::ord))
            .ifPresent(next ->
                jdbcTemplate.update(REANCHOR_BLOCK_SQL, next.executableId(), block.id()));
    }

    /** Drops lead measures travelling with companions (ADR-027 D4) from a creation's member list. */
    private List<UUID> filterWigCompanions(List<UUID> members, List<String> rejections) {
        if (members.size() <= 1) {
            return members;
        }
        List<UUID> survivors = new ArrayList<>();
        for (UUID member : members) {
            if (LEAD_MEASURE.equals(executableType(member))) {
                rejections.add("Member rejected: a lead measure rides alone (WIG atomicity)");
            } else {
                survivors.add(member);
            }
        }
        return survivors;
    }

    private boolean violatesWalls(UUID userId, UUID blockId, OffsetDateTime start,
                                  OffsetDateTime end, List<String> rejections) {
        Boolean agendaHit = jdbcTemplate.queryForObject(AGENDA_WALL_OVERLAP_SQL, Boolean.class,
            userId, toTimestamp(end), toTimestamp(start));
        if (Boolean.TRUE.equals(agendaHit)) {
            rejections.add("Window rejected: overlaps a read-only AGENDA event (ADR-009)");
            return true;
        }
        Boolean blockHit = jdbcTemplate.queryForObject(BLOCK_WALL_OVERLAP_SQL, Boolean.class,
            userId, blockId, toTimestamp(end), toTimestamp(start));
        if (Boolean.TRUE.equals(blockHit)) {
            rejections.add("Window rejected: overlaps another scheduled block");
            return true;
        }
        return false;
    }

    private boolean isWigAtomic(UUID blockId) {
        return Boolean.TRUE.equals(
            jdbcTemplate.queryForObject(BLOCK_HAS_LEAD_MEMBER_SQL, Boolean.class, blockId));
    }

    private String executableType(UUID executableId) {
        List<String> types = jdbcTemplate.queryForList(EXECUTABLE_TYPE_SQL, String.class, executableId);
        return types.isEmpty() ? null : types.get(0);
    }

    /** The user-local calendar day of an instant — the same projection the D5 trigger derives. */
    private LocalDate localDay(UUID userId, OffsetDateTime instant) {
        List<String> zones = jdbcTemplate.queryForList(USER_ZONE_SQL, String.class, userId);
        ZoneId zone = zones.isEmpty() || zones.get(0) == null
            ? ZoneId.of("America/Bogota")
            : ZoneId.of(zones.get(0));
        return instant.atZoneSameInstant(zone).toLocalDate();
    }

    // ── Row mapping ───────────────────────────────────────────────────────────

    private List<TimeBlockSnapshot> queryBlocks(String sql, Object... args) {
        Map<UUID, BlockAccumulator> accumulators = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            UUID blockId = rs.getObject("id", UUID.class);
            BlockAccumulator accumulator = accumulators.get(blockId);
            if (accumulator == null) {
                accumulator = new BlockAccumulator(
                    rs.getObject("user_id", UUID.class),
                    toOffset(rs.getTimestamp("date_start")),
                    toOffset(rs.getTimestamp("date_end")),
                    rs.getString("status"),
                    rs.getString("origin"),
                    rs.getString("theme"),
                    rs.getString("reason"),
                    rs.getObject("planned_minutes", Integer.class),
                    rs.getObject("actual_duration_minutes", Integer.class),
                    toOffset(rs.getTimestamp("settled_at")));
                accumulators.put(blockId, accumulator);
            }
            UUID memberId = rs.getObject("member_id", UUID.class);
            if (memberId != null) {
                accumulator.members().add(new TimeBlockMemberSnapshot(
                    memberId, rs.getString("member_name"),
                    rs.getInt("member_minutes"), rs.getInt("member_ord")));
            }
        }, args);
        return accumulators.entrySet().stream()
            .map(entry -> new TimeBlockSnapshot(
                entry.getKey(), entry.getValue().userId(),
                entry.getValue().start(), entry.getValue().end(),
                entry.getValue().status(), entry.getValue().origin(),
                entry.getValue().theme(), entry.getValue().reason(),
                entry.getValue().plannedMinutes(), entry.getValue().actualMinutes(),
                entry.getValue().settledAt(),
                entry.getValue().members()))
            .toList();
    }

    private record BlockAccumulator(UUID userId, OffsetDateTime start, OffsetDateTime end,
                                    String status, String origin, String theme, String reason,
                                    Integer plannedMinutes, Integer actualMinutes,
                                    OffsetDateTime settledAt,
                                    List<TimeBlockMemberSnapshot> members) {

        BlockAccumulator(UUID userId, OffsetDateTime start, OffsetDateTime end, String status,
                         String origin, String theme, String reason, Integer plannedMinutes,
                         Integer actualMinutes, OffsetDateTime settledAt) {
            this(userId, start, end, status, origin, theme, reason, plannedMinutes, actualMinutes,
                settledAt, new ArrayList<>());
        }
    }

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt != null ? Timestamp.from(odt.toInstant()) : null;
    }

    private static OffsetDateTime toOffset(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
