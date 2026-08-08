package com.hyperbrain.planner;

import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies slot authority (ADR-026 D6) against a real PostgreSQL.
 *
 * <p>D5's reschedule seed — re-placing a task at the hour of its last expired block — is gone with the
 * cursor sweep it served (ADR-040). Under the window model a task is not re-timed at all: it becomes a
 * member of the window that holds it, and it is the window that carries the hour. The seed had no
 * successor to point at, so its tests went with it rather than being re-pointed at nothing.
 *
 * <ul>
 *   <li><b>D6 — slot authority / user override.</b> Work the user timed by hand — held by a
 *       {@code USER}-origin block, by a block he made himself in Notion, by one already under way or by
 *       one already closed — is not even offered to the floor, so a replan cannot quietly pull it out of
 *       the block the user put it in. Containment being monovalent (ADR-039), that guard has to live in
 *       the admission, not in the persistence: by the time a block is written the move has already
 *       happened. The set that <em>does</em> give its members up is exactly the regenerable one — the
 *       blocks this run is about to re-place — because no state of a real block means «this membership
 *       is free».</li>
 * </ul>
 */
@IntegrationTest
@DisplayName("Reschedule seed (D5) and slot authority / user override (D6) — ADR-026")
class RescheduleAndSlotAuthorityIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate TARGET = LocalDate.of(2026, 7, 10);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlannerStateRepository repository;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    @Test
    @DisplayName("D6: work the user timed by hand is not even a candidate — your authority outlives the replan")
    void user_override_is_not_a_planner_candidate() {
        UUID task = insertTask("User-timed task");
        // The user moved the task to 15:00; on the deployed model that is a USER-origin TIME_BLOCK
        // executable holding it through container_block_id (ADR-039).
        UUID userBlock = insertUserBlock(at(2026, 7, 10, 15), at(2026, 7, 10, 16));
        contain(userBlock, task);

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd(), dayStart());

        // The task never reaches the floor, so no plan can quietly pull it out of the block the user
        // put it in. Containment is monovalent: had it been offered, placing it WOULD have moved it.
        assertThat(ranked).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, task))
            .isEqualTo(userBlock);
    }

    @Test
    @DisplayName("D6: work held by the planner's own still-planned block stays a candidate — that is what a replan re-places")
    void planner_held_work_remains_a_candidate() {
        UUID task = insertTask("Planner-timed task");
        UUID plannerBlock = insertPlannerBlock(at(2026, 7, 10, 9), at(2026, 7, 10, 10));
        contain(plannerBlock, task);

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd(), dayStart());

        assertThat(ranked).singleElement().satisfies(executable ->
            assertThat(executable.id()).isEqualTo(task));
    }

    @Test
    @DisplayName("a block he made by hand holds its work whatever state it is in — TODO is not «free»")
    void work_in_a_hand_made_block_is_not_a_candidate() {
        UUID task = insertTask("Facturas");
        // A block Daniel creates in Notion is born through the ordinary ingestion of an executable: it
        // arrives as TODO, with no origin at all. A status whitelist did not recognise it, so every
        // replan emptied his blocks and dealt their work around the day.
        UUID handMade = insertHandMadeBlock(at(2026, 7, 10, 19), at(2026, 7, 10, 22));
        contain(handMade, task);

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd(), dayStart());

        assertThat(ranked).isEmpty();
    }

    @Test
    @DisplayName("a closed block keeps its work too: only the blocks this run may re-place give theirs up")
    void work_in_a_closed_block_is_not_a_candidate() {
        UUID task = insertTask("Ya hecha dentro de un bloque cerrado");
        UUID closed = insertBlockExecutable("PLANNER", "DONE", at(2026, 7, 10, 9), at(2026, 7, 10, 10));
        contain(closed, task);

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd(), dayStart());

        assertThat(ranked).isEmpty();
    }

    @Test
    @DisplayName("a FOCUS row holds nothing: the task it accounts for is still the day's to place")
    void work_pointed_at_by_a_focus_row_is_still_a_candidate() {
        // FOCUS is the one exclusion of the guard, and it has to stay one. A focus row is retrospective
        // accounting of work already running, never an arrangement of the user's — treating it as a
        // holder would take a task he started this morning out of the day for good, since nothing ever
        // releases it.
        UUID task = insertTask("Lo que estoy ejecutando ahora");
        UUID focus = insertBlockExecutable("FOCUS", "IN_PROGRESS",
            at(2026, 7, 10, 9), at(2026, 7, 10, 10));
        contain(focus, task);

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd(), dayStart());

        assertThat(ranked).singleElement().satisfies(executable ->
            assertThat(executable.id()).isEqualTo(task));
    }

    @Test
    @DisplayName("D8: work inside a planner block that already started stays there — the past is not re-placed")
    void work_in_a_started_block_is_not_a_candidate() {
        UUID task = insertTask("Mid-morning task");
        UUID started = insertPlannerBlock(at(2026, 7, 10, 9), at(2026, 7, 10, 11));
        contain(started, task);

        // A replan fired at 10:00 may not re-place what a block already under way is holding.
        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd(), at(2026, 7, 10, 10));

        assertThat(ranked).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, task))
            .isEqualTo(started);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private OffsetDateTime dayStart() {
        return TARGET.atStartOfDay(ZONE).toOffsetDateTime();
    }

    private OffsetDateTime dayEnd() {
        return TARGET.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime at(int year, int month, int day, int hour) {
        return OffsetDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    private UUID insertTask(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status)
            VALUES (?, ?, ?, 'TASK', 'TODO')
            """, id, USER, name);
        return id;
    }

    private UUID insertRawBlock(UUID executableId, OffsetDateTime start, OffsetDateTime end,
                                String status, String origin) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, executableId, start, end, status, origin);
        return id;
    }

    private UUID insertUserBlock(OffsetDateTime start, OffsetDateTime end) {
        return insertBlockExecutable("USER", start, end);
    }

    private UUID insertPlannerBlock(OffsetDateTime start, OffsetDateTime end) {
        return insertBlockExecutable("PLANNER", start, end);
    }

    /** A block as it arrives from Notion: the initial status of any executable, and no origin. */
    private UUID insertHandMadeBlock(OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, 'Oficio', 'TIME_BLOCK', 'TODO', ?, ?)
            """, id, USER, start, end);
        return id;
    }

    private UUID insertBlockExecutable(String origin, OffsetDateTime start, OffsetDateTime end) {
        return insertBlockExecutable(origin, "PLANNED", start, end);
    }

    private UUID insertBlockExecutable(String origin, String status, OffsetDateTime start,
                                       OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', ?, ?, ?, ?)
            """, id, USER, origin + " block", status, origin, start, end);
        return id;
    }

    private void contain(UUID blockId, UUID memberId) {
        jdbcTemplate.update(
            "UPDATE core_executable SET container_block_id = ?, container_ord = 0 WHERE id = ?",
            blockId, memberId);
    }
}
