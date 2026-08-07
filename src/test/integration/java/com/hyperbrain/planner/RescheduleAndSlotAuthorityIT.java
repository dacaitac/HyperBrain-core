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
 * Verifies the two facts ADR-026 D5/D6 pin down against a real PostgreSQL:
 * <ul>
 *   <li><b>D5 — reschedule of a vencido block.</b> {@code loadRankedExecutables} surfaces, as the
 *       executable's {@code rescheduleSeed}, the wall-clock hour of its last {@code EXPIRED} PLANNER
 *       block projected onto the target day (ADR-026 D3) — and only when no live block remains, so a
 *       still-placed task keeps the Planner's full authorship (D4).</li>
 *   <li><b>D6 — slot authority / user override.</b> Work the user timed by hand — held by a
 *       {@code USER}-origin block, or by one already under way — is not even offered to the floor, so a
 *       replan cannot quietly pull it out of the block the user put it in. Containment being monovalent
 *       (ADR-039), that guard has to live in the admission, not in the persistence: by the time a block
 *       is written the move has already happened.</li>
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
    @DisplayName("D5: the reschedule seed is the hour of the last EXPIRED block, projected onto the target day")
    void reschedule_seed_derives_the_hour_of_the_last_expired_block() {
        UUID task = insertTask("Overdue task");
        // The task's last block ran the day before at 15:00–16:00 and expired unattended.
        insertRawBlock(task, at(2026, 7, 9, 15), at(2026, 7, 9, 16), "EXPIRED", "PLANNER");

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd());

        assertThat(ranked).singleElement().satisfies(executable -> assertThat(executable.rescheduleSeed())
            .isEqualTo(at(2026, 7, 10, 15)));
    }

    @Test
    @DisplayName("D5: the most recent EXPIRED block wins the seed hour")
    void reschedule_seed_uses_the_most_recent_expired_block() {
        UUID task = insertTask("Twice-expired task");
        insertRawBlock(task, at(2026, 7, 7, 9), at(2026, 7, 7, 10), "EXPIRED", "PLANNER");
        insertRawBlock(task, at(2026, 7, 9, 15), at(2026, 7, 9, 16), "EXPIRED", "PLANNER");

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd());

        assertThat(ranked).singleElement().satisfies(executable -> assertThat(executable.rescheduleSeed())
            .isEqualTo(at(2026, 7, 10, 15)));
    }

    @Test
    @DisplayName("D5: a task that still holds a live block carries no seed — the Planner keeps full authorship (D4)")
    void no_seed_while_a_live_block_remains() {
        UUID task = insertTask("Still-planned task");
        insertRawBlock(task, at(2026, 7, 9, 15), at(2026, 7, 9, 16), "EXPIRED", "PLANNER");
        insertRawBlock(task, at(2026, 7, 10, 8), at(2026, 7, 10, 9), "PLANNED", "PLANNER");

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd());

        assertThat(ranked).singleElement().satisfies(executable ->
            assertThat(executable.rescheduleSeed()).isNull());
    }

    @Test
    @DisplayName("D5: a task with no block at all carries no seed")
    void no_seed_without_any_prior_block() {
        insertTask("Fresh task");

        List<SchedulableExecutable> ranked =
            repository.loadRankedExecutables(USER, dayStart(), dayEnd());

        assertThat(ranked).singleElement().satisfies(executable ->
            assertThat(executable.rescheduleSeed()).isNull());
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
            repository.loadRankedExecutables(USER, dayStart(), dayEnd());

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
            repository.loadRankedExecutables(USER, dayStart(), dayEnd());

        assertThat(ranked).singleElement().satisfies(executable ->
            assertThat(executable.id()).isEqualTo(task));
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

    private UUID insertBlockExecutable(String origin, OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', 'PLANNED', ?, ?, ?)
            """, id, USER, origin + " block", origin, start, end);
        return id;
    }

    private void contain(UUID blockId, UUID memberId) {
        jdbcTemplate.update(
            "UPDATE core_executable SET container_block_id = ?, container_ord = 0 WHERE id = ?",
            blockId, memberId);
    }
}
