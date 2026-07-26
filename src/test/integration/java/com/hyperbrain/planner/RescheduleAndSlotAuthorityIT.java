package com.hyperbrain.planner;

import com.hyperbrain.planner.domain.model.AgendaBlock;
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
 *   <li><b>D6 — slot authority / user override.</b> A user's time edit, captured as a {@code USER}-origin
 *       block (ADR-018), is <b>not overwritten by the next replan</b>: the reconciliation is scoped to
 *       {@code PLANNER}-origin rows, so the {@code USER} block and its live membership survive untouched
 *       while the planner block for the same task is skipped rather than stealing the slot.</li>
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
    @DisplayName("D6: a user's time-edit (USER-origin block) survives the next replan — it is never overwritten")
    void user_override_is_not_overwritten_by_the_replan() {
        UUID task = insertTask("User-timed task");
        // The user moved the block to 15:00; this is captured as a USER-origin live block (ADR-018)
        // with its live membership on the bridge table.
        UUID userBlock = insertRawBlock(task, at(2026, 7, 10, 15), at(2026, 7, 10, 16), "PLANNED", "USER");
        insertMember(userBlock, task);

        // The next replan wants the same task at 09:00.
        List<UUID> removed = repository.reconcilePlannedBlocks(USER, TARGET, ZONE,
            List.of(new AgendaBlock(task, at(2026, 7, 10, 9), at(2026, 7, 10, 10), false, false,
                "Ranked by priority")));

        // The USER block is untouched: same id, same 15:00 window, still USER/PLANNED.
        assertThat(removed).doesNotContain(userBlock);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block WHERE id = ?", OffsetDateTime.class, userBlock))
            .isEqualTo(at(2026, 7, 10, 15));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_time_block WHERE id = ?", String.class, userBlock))
            .isEqualTo("USER");

        // The task's single live membership is still on the USER block — the planner projection did not
        // steal the slot (the conflicting bridge row was skipped, D5 index honoured).
        List<UUID> liveHosts = jdbcTemplate.queryForList("""
            SELECT block_id FROM core_time_block_member
            WHERE executable_id = ? AND block_status IN ('PLANNED', 'ACTIVE')
            """, UUID.class, task);
        assertThat(liveHosts).containsExactly(userBlock);
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

    private void insertMember(UUID blockId, UUID executableId) {
        jdbcTemplate.update("""
            INSERT INTO core_time_block_member (block_id, executable_id, planned_minutes, ord)
            VALUES (?, ?, 60, 0)
            """, blockId, executableId);
    }
}
