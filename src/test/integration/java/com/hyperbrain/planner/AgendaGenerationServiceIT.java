package com.hyperbrain.planner;

import com.hyperbrain.planner.application.AgendaGenerationService;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.PlannerBlockView;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Verifies the deterministic agenda-generation pipeline (#6a) against a real PostgreSQL: the
 * {@link AgendaGenerationService} reads the day's aggregates, materializes {@code PLANNED} blocks,
 * re-imposes the hard walls, and persists the result. Black-box: only the public application service
 * is exercised; the domain rules themselves are unit-tested.
 */
@IntegrationTest
@DisplayName("AgendaGenerationService — deterministic floor pipeline (#6a)")
class AgendaGenerationServiceIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, UTC);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgendaGenerationService service;
    @Autowired private HumanizationSettings humanizationSettings;

    @Test
    @DisplayName("the deployed application.yml really binds a plausible band onto every meal anchor")
    void the_deployed_meal_bands_are_bound() {
        // The keys are optional and their absence is silent — an unbound band-start simply makes the
        // meal rigid — so the shipped configuration is asserted against the running context rather
        // than against the record's defaults.
        assertThat(humanizationSettings.mealWindows())
            .extracting(MealWindow::label, MealWindow::start, MealWindow::end,
                MealWindow::bandStart, MealWindow::bandEnd)
            .containsExactly(
                tuple("breakfast", LocalTime.of(7, 0), LocalTime.of(7, 30),
                    LocalTime.of(5, 30), LocalTime.of(10, 0)),
                tuple("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
                    LocalTime.of(11, 30), LocalTime.of(14, 30)),
                tuple("dinner", LocalTime.of(19, 0), LocalTime.of(20, 0),
                    LocalTime.of(18, 0), LocalTime.of(21, 30)));
    }

    @BeforeEach
    void cleanState() throws Exception {
        PlannerBlockView.create(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        jdbcTemplate.update("UPDATE sys_user SET settings = '{}'::jsonb WHERE id = ?", USER);
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        // Pin the user to UTC so the fixtures' UTC instants and the frontier's local projection agree.
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    @Test
    @DisplayName("materializes PLANNED blocks for ranked executables and persists them")
    void materializes_and_persists_blocks() {
        UUID high = insertTask("High", 0.9, 60);
        UUID low = insertTask("Low", 0.3, 60);

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        assertThat(agenda.blocks()).hasSize(2);
        List<Map<String, Object>> persisted = jdbcTemplate.queryForList(
            "SELECT executable_id, status, origin FROM planner_blocks ORDER BY date_start");
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).get("executable_id")).isEqualTo(high);
        assertThat(persisted).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PLANNED");
            assertThat(row.get("origin")).isEqualTo("PLANNER");
        });
        assertThat(persisted.get(1).get("executable_id")).isEqualTo(low);
    }

    @Test
    @DisplayName("wall: a read-only AGENDA executable window is never scheduled over")
    void does_not_schedule_over_agenda_wall() {
        // AGENDA occupies 07:00-09:00 (within the default cold-start wake window).
        insertAgendaEvent("Meeting",
            OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        UUID task = insertTask("Work", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM planner_blocks WHERE executable_id = ?",
            OffsetDateTime.class, task);
        // The block cannot start before the AGENDA wall ends at 09:00.
        assertThat(blockStart).isAfterOrEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        // And the AGENDA executable itself is never given a block.
        Integer agendaBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks b JOIN core_executable e ON e.id = b.executable_id "
                + "WHERE e.type = 'AGENDA'", Integer.class);
        assertThat(agendaBlocks).isZero();
    }

    @Test
    @DisplayName("wall: a real TIME_BLOCK executable (ADR-039 model) is never scheduled over")
    void does_not_schedule_over_a_time_block_executable() {
        // A block that already holds 07:00-09:00 of the day. Since ADR-039 it lives in
        // core_executable, so a Planner reading the frozen table would not see it and would plan on
        // top of it.
        insertTimeBlockExecutable("Morning window",
            OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        UUID task = insertTask("Work", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM planner_blocks WHERE executable_id = ?",
            OffsetDateTime.class, task);
        assertThat(blockStart).isAfterOrEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("wall: no window is laid on a standing ACTIVITY — movable in hour is not invisible")
    void does_not_lay_a_window_over_a_standing_activity() {
        // The production day, reproduced. «Desayunar» is an ACTIVITY holding 10:30-11:30, straddling the
        // «Descanso» band (10:30-11:00) and the first hour of «Oficio» (11:00-13:00). It can never be a
        // window member — it already is a block of time in its own right — and it was not read as
        // occupancy either, so it belonged to neither list and the day was laid straight over it:
        // «Descanso» at 10:30 and «Oficio» at 11:00, both on top of breakfast.
        insertActivity("Desayunar", at(10, 30), at(11, 30));
        // Enough work that the floor deals a member into every band of the day, «Descanso» and «Oficio»
        // included: a day too light to reach them would pass this test for the wrong reason.
        for (int i = 0; i < 14; i++) {
            insertTask("Tarea " + i, 0.9 - i * 0.01, 30);
        }

        service.generate(USER, DAY, UTC, NOON, false);

        // The day is really planned, and not one of its windows lands on breakfast. Naming the
        // offenders is deliberate: the failure reads back as the production report did.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE origin = 'PLANNER'", Integer.class))
            .isGreaterThan(5);
        List<String> trespassers = jdbcTemplate.queryForList("""
            SELECT theme FROM planner_blocks
            WHERE date_start < ? AND date_end > ?
            """, String.class, at(11, 30), at(10, 30));
        assertThat(trespassers).isEmpty();
        // And the activity itself is never given a block: it is a wall, not a candidate.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks b JOIN core_executable e ON e.id = b.executable_id "
                + "WHERE e.type = 'ACTIVITY'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a commitment re-timed by this run walls where it LANDED — the day is not laid twice")
    void a_rescued_commitment_walls_at_its_new_hour() {
        // Both halves of the fix meet here. The study session at 09:00 was run over by the interruption
        // the replan is a response to, so the rescue sends it a later hour of its own day; the lunch at
        // 14:30 is still ahead, so nobody touches it. Whatever the rescue decides, the day must end up
        // with nothing sitting on either of them — the one at its NEW hour, the other where it stands.
        UUID study = insertCommitment("Repaso", "LEARNING_SESSION", at(9, 0), at(10, 0));
        UUID lunch = insertCommitment("Almuerzo", "ACTIVITY", at(14, 30), at(15, 30));
        for (int i = 0; i < 10; i++) {
            insertTask("Tarea " + i, 0.9 - i * 0.01, 45);
        }

        service.generate(USER, DAY, UTC, NOON, true);

        // The study session kept its day and lost only its hour.
        OffsetDateTime movedStart = jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, study);
        OffsetDateTime movedEnd = jdbcTemplate.queryForObject(
            "SELECT end_time FROM core_executable WHERE id = ?", OffsetDateTime.class, study);
        assertThat(movedStart).isAfterOrEqualTo(NOON).isBefore(at(23, 59));
        assertThat(java.time.Duration.between(movedStart, movedEnd)).isEqualTo(java.time.Duration.ofHours(1));
        // The lunch still ahead was never touched: recovering the hour is not a licence to rearrange
        // a calendar the user wrote.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, lunch))
            .isEqualTo(at(14, 30));
        // And nothing this run planned stands on either window — the new one included, which is the
        // whole point of a move rewriting its own wall.
        assertThat(blocksOverlapping(movedStart, movedEnd)).isEmpty();
        assertThat(blocksOverlapping(at(14, 30), at(15, 30))).isEmpty();
        // The day really was planned; an empty day would satisfy the above for the wrong reason.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE origin = 'PLANNER'", Integer.class))
            .isGreaterThan(3);
    }

    @Test
    @DisplayName("a settled commitment gives its hour back to the day instead of walling it")
    void a_settled_commitment_releases_its_window() {
        // The one place a commitment parts ways with a block, which walls in every state: the user can
        // settle a commitment ahead of its hour, and an activity already done does not occupy the
        // future. A whitelist of open states would have kept the hour reserved for nothing.
        insertCommitment("Desayunar", "ACTIVITY", "DONE", at(10, 30), at(11, 30));
        for (int i = 0; i < 14; i++) {
            insertTask("Tarea " + i, 0.9 - i * 0.01, 30);
        }

        service.generate(USER, DAY, UTC, NOON, false);

        assertThat(blocksOverlapping(at(10, 30), at(11, 30))).isNotEmpty();
    }

    /** The themes of the run's blocks standing on the given window — named so a failure reads back. */
    private List<String> blocksOverlapping(OffsetDateTime start, OffsetDateTime end) {
        return jdbcTemplate.queryForList("""
            SELECT theme FROM planner_blocks WHERE date_start < ? AND date_end > ?
            """, String.class, end, start);
    }

    @Test
    @DisplayName("F1: the WIG lead measure of the active MCI is reserved first")
    void reserves_wig_first() {
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID wig = insertLeadMeasure("Predictive action", mci, 0.5);
        insertTask("Whirlwind", 0.99, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        // The lead measure holds a goal window, and holds it ALONE: the band the template calls
        // «Meta» is for the goal. It no longer has to be the earliest block of the day — under the
        // window model the goal takes its band, not the first free minute.
        UUID goalBlock = jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, wig);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT template_slot_id FROM core_executable WHERE id = ?", String.class, goalBlock))
            .isEqualTo("GOAL_MORNING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE container_block_id = ?",
            Integer.class, goalBlock)).isEqualTo(1);
    }

    @Test
    @DisplayName("F1: every active MCI with a lead measure gets a reserved block (portfolio, not one WIG)")
    void reserves_a_block_per_active_mci() {
        UUID mciA = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID mciB = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID wigA = insertLeadMeasure("Lead A", mciA, 0.5);
        UUID wigB = insertLeadMeasure("Lead B", mciB, 0.4);

        service.generate(USER, DAY, UTC, NOON, false);

        List<UUID> wigBlocks = jdbcTemplate.queryForList(
            "SELECT executable_id FROM planner_blocks WHERE executable_id IN (?, ?)",
            UUID.class, wigA, wigB);
        assertThat(wigBlocks).containsExactlyInAnyOrder(wigA, wigB);
    }

    @Test
    @DisplayName("F1: an active MCI with no lead measure yields no block (surfaced as an alert, never NaN)")
    void active_mci_without_lead_measure_is_not_reserved() {
        insertCycle("MCI", "ACTIVE", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID task = insertTask("Whirlwind", 0.9, 60);

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        // The MCI carries no lead measure, so no WIG block is reserved; only the ranked task lands.
        assertThat(agenda.blocks()).hasSize(1);
        assertThat(agenda.blocks().get(0).executableId()).isEqualTo(task);
        assertThat(agenda.blocks()).noneMatch(b -> b.wig());
    }

    @Test
    @DisplayName("F1: the WIG portfolio spans the MCI subtree and tolerates progress + prior-block history")
    void wig_portfolio_reads_subtree_and_history() {
        // MCI with a child cycle: a root executable in the child (60% progress) contributes to the
        // MCI's aggregated progress, and a lead measure lives in the subtree.
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID child = insertChildCycle("PROJECT", "ACTIVE", mci);
        UUID wig = insertLeadMeasure("Lead in subtree", child, 0.5);
        insertRootExecutableWithProgress(child, 0.6, 120);
        // A prior-day planner block for the lead measure exercises the history columns.
        insertPlannerBlock(wig, NOON.minusDays(1));

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        // The subtree lead measure is reserved as the WIG despite living under a descendant cycle.
        assertThat(agenda.blocks()).filteredOn(b -> b.wig()).extracting(b -> b.executableId())
            .containsExactly(wig);
    }

    @Test
    @DisplayName("observed sleep frontier: fresh records (relative to the injected clock) shift the window off the cold-start default")
    void observed_frontier_replaces_cold_start() {
        // Five nights waking at 05:00 UTC, well before the cold-start default. Everything is seeded
        // relative to the injected reference instant (NOON), not the wall clock: collected 2 h before
        // NOON they are fresh only against the injected clock — against the DB server clock they would
        // be many days stale and dropped. The freshness cut is therefore deterministic and pinned.
        OffsetDateTime collectedAt = NOON.minusHours(2);
        for (int i = 1; i <= 5; i++) {
            OffsetDateTime wake = NOON.minusDays(i)
                .withHour(5).withMinute(0).withSecond(0).withNano(0);
            insertSleepRecord(wake.withHour(0), wake, 80, collectedAt);
        }
        UUID task = insertTask("Early bird", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM planner_blocks WHERE executable_id = ?",
            OffsetDateTime.class, task);
        // Wake observed at 05:00 lets the block start earlier than the cold-start default would.
        assertThat(blockStart).isEqualTo(OffsetDateTime.of(2026, 7, 10, 5, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("stale sleep frontier: records past the freshness bound (injected clock) fall back to the settings window")
    void stale_frontier_falls_back_to_settings_window() {
        // An explicit settings wake so the fallback is deterministic and independent of the hard
        // default constant.
        jdbcTemplate.update(
            "UPDATE sys_user SET settings = ?::jsonb WHERE id = ?",
            "{\"planner_constraints\":{\"sleep_window\":{\"wake\":\"08:00\",\"bedtime\":\"23:00\"}}}",
            USER);
        // Same 05:00 wake history, but collected 40 h before NOON — past the 36 h freshness bound
        // measured from the injected clock, so the whole history is dropped and the settings window
        // wins. (A DB-server-clock guard could not tell this case apart from the fresh one above.)
        OffsetDateTime collectedAt = NOON.minusHours(40);
        for (int i = 1; i <= 5; i++) {
            OffsetDateTime wake = NOON.minusDays(i)
                .withHour(5).withMinute(0).withSecond(0).withNano(0);
            insertSleepRecord(wake.withHour(0), wake, 80, collectedAt);
        }
        UUID task = insertTask("Sleeper", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM planner_blocks WHERE executable_id = ?",
            OffsetDateTime.class, task);
        // The stale 05:00 observations are ignored; the block opens at the settings wake of 08:00.
        assertThat(blockStart).isEqualTo(OffsetDateTime.of(2026, 7, 10, 8, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("stable identity: regenerating the day keeps each block's id, never accumulating rows")
    void regeneration_preserves_block_identity() {
        UUID high = insertTask("High", 0.9, 60);
        UUID low = insertTask("Low", 0.3, 60);

        service.generate(USER, DAY, UTC, NOON, false);
        UUID highBlockFirst = blockId(high);
        UUID lowBlockFirst = blockId(low);

        // A second run on the same day converges onto the same rows (identity-stable UPDATE), so the
        // block keeps its id — and therefore its Apple mapping / EKEvent (no duplication, #15).
        service.generate(USER, DAY, UTC, NOON, false);
        Integer afterSecond = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE origin = 'PLANNER' AND status = 'PLANNED'",
            Integer.class);

        assertThat(afterSecond).isEqualTo(2);
        assertThat(blockId(high)).isEqualTo(highBlockFirst);
        assertThat(blockId(low)).isEqualTo(lowBlockFirst);
    }

    @Test
    @DisplayName("reconciliation: a task dropped from the plan removes its block and stages its id for deletion")
    void regeneration_removes_dropped_block_and_reports_it() {
        UUID high = insertTask("High", 0.9, 60);
        UUID low = insertTask("Low", 0.3, 60);

        service.generate(USER, DAY, UTC, NOON, false);
        UUID highBlockFirst = blockId(high);
        UUID lowBlockFirst = blockId(low);
        // Isolate the second run's outbox event.
        jdbcTemplate.update("DELETE FROM outbox_events");

        // The low task is completed, so the replan no longer schedules it.
        jdbcTemplate.update("UPDATE core_executable SET status = 'DONE' WHERE id = ?", low);
        service.generate(USER, DAY, UTC, NOON, false);

        // The high block survives with the same id; the low block row is gone.
        assertThat(blockId(high)).isEqualTo(highBlockFirst);
        Integer lowBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE executable_id = ?", Integer.class, low);
        assertThat(lowBlocks).isZero();

        // And the withdrawal is announced as a deletion of the block executable, carrying its type so
        // Apple removes the calendar event rather than looking for a reminder that never existed.
        String payload = jdbcTemplate.queryForObject(
            "SELECT payload FROM outbox_events WHERE event_type = 'ExecutableDeletedEvent'",
            String.class);
        assertThat(payload).contains(lowBlockFirst.toString()).contains("TIME_BLOCK");
    }

    @Test
    @DisplayName("a replan admits new work into the window that already exists, keeping its id")
    void a_replan_admits_new_work_without_disturbing_the_window() {
        UUID high = insertTask("High", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);
        UUID highBlockFirst = blockId(high);
        jdbcTemplate.update("DELETE FROM outbox_events");

        UUID added = insertTask("Added", 0.5, 60);
        service.generate(USER, DAY, UTC, NOON, false);

        // The window keeps its identity — so its calendar event is updated, never duplicated — and the
        // newcomer joins it rather than opening a block of its own. The plan is conserved, not frozen.
        assertThat(blockId(high)).isEqualTo(highBlockFirst);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, added))
            .isEqualTo(highBlockFirst);
        // Nothing was withdrawn, so nothing announces a deletion.
        Integer deletions = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'ExecutableDeletedEvent'",
            Integer.class);
        assertThat(deletions).isZero();
    }

    @Test
    @DisplayName("idempotent convergence: a FOCUS/USER block on the same day survives regeneration")
    void regeneration_preserves_non_planner_blocks() {
        UUID task = insertTask("High", 0.9, 60);
        // A USER-origin block on the same day must not be cleared by the planner delete.
        UUID userBlock = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, 'User block', 'TIME_BLOCK', 'PLANNED', 'USER', ?, ?)
            """, userBlock,
            USER,
            OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 16, 0, 0, 0, UTC));
        jdbcTemplate.update(
            "UPDATE core_executable SET container_block_id = ?, container_ord = 0 WHERE id = ?",
            userBlock, task);

        service.generate(USER, DAY, UTC, NOON, false);

        Integer userBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE origin = 'USER'", Integer.class);
        assertThat(userBlocks).isEqualTo(1);
    }

    @Test
    @DisplayName("the day Daniel pre-organised survives the run: work inside his own block stays in it")
    void work_inside_a_hand_made_block_is_left_alone() {
        // The production case, and the reason this test exists. Daniel leaves the day laid out the
        // night before: his own block with the work he put inside it. A block he creates in Notion is
        // born through the ordinary ingestion of an executable, so it arrives as TODO with no origin at
        // all — and while the candidate guard demanded PLANNED/IN_PROGRESS it did not recognise the
        // block, so the run collected his tasks, dealt them around the day and left «WakeUP», «Oficio»
        // and «Torbellino» empty.
        UUID handMade = insertHandMadeBlock("Oficio", at(7, 0), at(10, 0));
        UUID hisWork = insertTask("Facturas", 0.95, 60);
        contain(handMade, hisWork);
        // Enough other work that the run really does fill the day around him.
        for (int i = 0; i < 8; i++) {
            insertTask("Tarea " + i, 0.8 - i * 0.01, 30);
        }

        service.generate(USER, DAY, UTC, NOON, false);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, hisWork))
            .isEqualTo(handMade);
        // And the day was genuinely planned around him — an empty day would pass the assertion above
        // for the wrong reason.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE origin = 'PLANNER'", Integer.class))
            .isGreaterThan(2);
    }

    @Test
    @DisplayName("the goal he placed in his own block is left there: even the WIG reservation stops at it")
    void the_wig_he_placed_by_hand_is_left_where_he_put_it() {
        // The other half of the same decision, and the one path that does not go through the candidate
        // guard at all: the F1 reservation takes the lead measure straight from the WIG portfolio. So
        // even after the guard learned to recognise his blocks, the goal work he had already placed in
        // one of them was pulled back out on every run and given a window of the planner's choosing.
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID lead = insertLeadMeasure("Acción predictiva", mci, 0.5);
        UUID hisBlock = insertHandMadeBlock("Meta de la noche", at(20, 0), at(22, 0));
        contain(hisBlock, lead);
        insertTask("Torbellino", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        // It is still in his block, and no goal window was reserved for it a second time.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, lead))
            .isEqualTo(hisBlock);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE executable_id = ? AND origin = 'PLANNER'",
            Integer.class, lead)).isZero();
    }

    @Test
    @DisplayName("a goal still sitting in yesterday's block is exactly the one that must get a window today")
    void the_wig_held_by_yesterdays_block_still_gets_its_window() {
        // The daily mechanic the hands-off set must leave intact. Yesterday's block is not a window of
        // today, so it holds no authority over today's plan: a lead measure left inside it is the very
        // case the F1 reservation exists for. Scoping the set to today's walls is what keeps «he
        // arranged this» from decaying into «it was placed once, never touch it again».
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID lead = insertLeadMeasure("Acción predictiva", mci, 0.5);
        insertPlannerBlock(lead, NOON.minusDays(1));

        Agenda agenda = service.generate(USER, DAY, UTC, NOON, false);

        assertThat(agenda.blocks()).filteredOn(b -> b.wig()).extracting(b -> b.executableId())
            .containsExactly(lead);
        assertThat(agenda.excluded()).extracting(e -> e.reason().name())
            .doesNotContain("ALREADY_HELD_BY_USER");
        // And it really moved into a window of today, out of the settled block of yesterday.
        assertThat(jdbcTemplate.queryForObject("""
            SELECT b.start_time FROM core_executable b
            JOIN core_executable m ON m.container_block_id = b.id
            WHERE m.id = ?
            """, OffsetDateTime.class, lead)).isAfterOrEqualTo(at(0, 0));
    }

    @Test
    @DisplayName("the run's own placement is not mistaken for his: a second run reserves the goal again")
    void a_replan_still_reserves_the_goal_window() {
        // The blocks this run is about to re-place are subtracted from the walls before the hands-off
        // set is read. Were they not, the plan would freeze itself: the goal the planner placed at the
        // first run would look like work the user had arranged, and no replan could ever move it.
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID lead = insertLeadMeasure("Acción predictiva", mci, 0.5);
        insertTask("Torbellino", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);
        Agenda replanned = service.generate(USER, DAY, UTC, NOON, false);

        assertThat(replanned.blocks()).filteredOn(b -> b.wig()).extracting(b -> b.executableId())
            .containsExactly(lead);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, lead))
            .isNotNull();
    }

    @Test
    @DisplayName("write-back staging: a planned day announces each block as an executable change")
    void planned_day_stages_agenda_block_event() {
        insertTask("High", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        Integer events = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'CORE_EXECUTABLE' "
                + "AND event_type = 'ExecutableCreatedEvent' AND aggregate_id IN "
                + "(SELECT id::text FROM core_executable WHERE type = 'TIME_BLOCK')", Integer.class);
        assertThat(events).isEqualTo(1);
        // The block is announced under its OWN id now, not under the user's: it is an executable like
        // any other, and its mirrors are reached by the standard propagators.
        String aggregateId = jdbcTemplate.queryForObject(
            "SELECT id::text FROM core_executable WHERE type = 'TIME_BLOCK'", String.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, aggregateId))
            .isPositive();
    }

    @Test
    @DisplayName("replan-from-now: no block is scheduled before the reference instant")
    void replan_respects_now() {
        insertTask("Afternoon work", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, true);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM planner_blocks", OffsetDateTime.class);
        assertThat(blockStart).isAfterOrEqualTo(NOON);
    }

    @Test
    @DisplayName("before-wake replan plans the full day on TODAY in the user timezone (not shifted to tomorrow)")
    void replan_before_wake_plans_full_day_today_in_user_timezone() {
        // The user is in America/Bogota (UTC-5). A replan fired at 04:23 local — before the ~07:00 wake —
        // must plan the whole of TODAY, whose window still lies ahead. Resolving in UTC instead would
        // read 04:23 local as 09:23 UTC (mid-morning), clamp today's window and shift blocks to tomorrow.
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'America/Bogota' WHERE id = ?", USER);
        java.time.ZoneId bogota = java.time.ZoneId.of("America/Bogota");
        for (int i = 0; i < 15; i++) {
            insertTaskDueOn("Task " + i, 0.99 - i * 0.02, 60, LocalDate.of(2026, 7, 22));
        }
        // 2026-07-22T09:23:36Z = 2026-07-22 04:23 America/Bogota → today = 07-22.
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 22, 9, 23, 36, 0, UTC);

        service.materializeReplanIfNew(USER, occurredAt, bogota);

        int todayBlocks = localDayCount(bogota, 2026, 7, 22);
        int tomorrowBlocks = localDayCount(bogota, 2026, 7, 23);
        // Today gets the work, since its full window lies ahead — and it keeps all of it: what does not
        // fit is no longer spilled onto tomorrow, because tomorrow only takes what is dated for tomorrow.
        assertThat(todayBlocks).isGreaterThan(tomorrowBlocks);
        // The day opens at 07:30 Bogota, never in the pre-wake hours: the personal-routine band runs
        // 07:00–08:00 from the local wake, and the breakfast anchor (07:00–07:30) takes its first half
        // hour, so work starts when breakfast ends. Pinned to the minute on purpose — this hour is the
        // one the meal anchors actually move.
        OffsetDateTime firstStartLocal = jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM planner_blocks WHERE origin = 'PLANNER'", OffsetDateTime.class)
            .atZoneSameInstant(bogota).toOffsetDateTime();
        assertThat(firstStartLocal.toLocalTime()).isEqualTo(java.time.LocalTime.of(7, 30));
        assertThat(firstStartLocal.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 22));
    }

    private int localDayCount(java.time.ZoneId zone, int year, int month, int day) {
        OffsetDateTime start = java.time.LocalDate.of(year, month, day).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = start.plusDays(1);
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE origin = 'PLANNER' AND status = 'PLANNED' "
                + "AND date_start >= ? AND date_start < ?", Integer.class, start, end);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("replan of the current day keeps it full: the dispatch's own PLANNED blocks are not walls")
    void replan_of_already_planned_day_keeps_today_full() {
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'America/Bogota' WHERE id = ?", USER);
        java.time.ZoneId bogota = java.time.ZoneId.of("America/Bogota");
        for (int i = 0; i < 20; i++) {
            insertTaskDueOn("Task " + i, 0.99 - i * 0.01, 60, LocalDate.of(2026, 7, 22));
        }
        // Morning dispatch (full-day) fills today near the occupancy cap.
        OffsetDateTime dispatch = OffsetDateTime.of(2026, 7, 22, 12, 0, 0, 0, UTC); // 07:00 Bogota
        service.generate(USER, java.time.LocalDate.of(2026, 7, 22), bogota, dispatch, false);
        int afterDispatch = localDayCount(bogota, 2026, 7, 22);

        // ~30 min later, a replan-from-now must keep today just as full — not exclude the tasks it
        // already materialized (treating their own dispatch blocks as walls) and push them to tomorrow.
        OffsetDateTime replan = OffsetDateTime.of(2026, 7, 22, 12, 39, 0, 0, UTC); // 07:39 Bogota
        service.generate(USER, java.time.LocalDate.of(2026, 7, 22), bogota, replan, true);
        int afterReplan = localDayCount(bogota, 2026, 7, 22);

        assertThat(afterDispatch).isGreaterThan(8); // the dispatch filled the day
        assertThat(afterReplan).isGreaterThanOrEqualTo(afterDispatch - 2); // the replan keeps it full
    }

    @Test
    @DisplayName("a TASK whose due date lives in start_time (DR-01) is scheduled on that day, not today")
    void task_due_in_start_time_is_scheduled_on_its_due_day() {
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'America/Bogota' WHERE id = ?", USER);
        java.time.ZoneId bogota = java.time.ZoneId.of("America/Bogota");
        LocalDate today = LocalDate.of(2026, 7, 22);
        UUID dueToday = insertTaskDueOn("Facturas", 0.90, 30, today);
        UUID dueTomorrow = insertTaskDueOn("Sleep management", 0.95, 30, today.plusDays(1));
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 22, 12, 0, 0, 0, UTC); // 07:00 Bogota today

        service.generate(USER, today, bogota, occurredAt, false);

        // The tomorrow-due task is not scheduled today (its start_time due date is respected)...
        assertThat(count("SELECT count(*) FROM planner_blocks WHERE executable_id = ?", dueTomorrow))
            .isZero();
        // ...while the one dated for today is.
        assertThat(count("SELECT count(*) FROM planner_blocks WHERE executable_id = ?", dueToday))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("admission: an undated task is inventory — no day ever takes it")
    void an_undated_task_is_never_admitted() {
        // The decision Daniel took on 2026-08-13, reversing the dateless half of ADR-040 D3: a day is
        // planned out of what is dated FOR it and nothing else. Drawing from the dateless bag filled the
        // day with work he had never put on it, so a task with no date now waits in inventory until it
        // gets one — by his hand or by the day-close sweep's re-dating.
        UUID undated = insertUndatedTask("Pedir cita en movilidad", 0.99, 30);
        UUID dated = insertTask("Facturas", 0.10, 30);

        service.generate(USER, DAY, UTC, NOON, false);

        // Even at the top of the ranking it is left out, while the lowest-ranked dated task is planned:
        // an empty day would satisfy the first assertion for the wrong reason.
        assertThat(scheduledExecutableIds()).contains(dated).doesNotContain(undated);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, undated))
            .isNull();
    }

    private int count(String sql, Object arg) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("admission: yesterday's leftovers are not dragged into today — the day-close sweep owns them")
    void an_overdue_task_is_not_admitted() {
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'America/Bogota' WHERE id = ?", USER);
        java.time.ZoneId bogota = java.time.ZoneId.of("America/Bogota");
        UUID overdue = insertTaskDueOn("Overdue", 0.9, 30, LocalDate.of(2026, 7, 21)); // due yesterday
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 22, 12, 0, 0, 0, UTC); // 07:00 Bogota

        service.generate(USER, java.time.LocalDate.of(2026, 7, 22), bogota, occurredAt, true);

        // The day takes what is dated FOR it, and nothing else (ADR-040 D3, as amended).
        // What was left undone yesterday returns through the day-close sweep, which re-dates it to
        // today; pulling it forward here as well would double-count it and undo the sweep's work.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, overdue))
            .isNull();
    }

    @Test
    @DisplayName("end-of-day replan: a zero-width window for today yields an empty day, never throwing")
    void replan_at_bedtime_yields_empty_today_without_throwing() {
        insertTask("Work", 0.9, 60);
        // T at the cold-start bedtime (23:00): the forward window for today is [23:00, 23:00] — zero width.
        OffsetDateTime bedtime = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, UTC);

        Agenda agenda = service.generate(USER, DAY, UTC, bedtime, true);

        // No exception, an empty day, and nothing persisted (today's plan, if any, is left untouched).
        assertThat(agenda.blocks()).isEmpty();
        Integer todayBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks", Integer.class);
        assertThat(todayBlocks).isZero();
    }

    @Test
    @DisplayName("end-of-day replan plans the NEXT day: the 48h window skips today's empty window and "
        + "materializes tomorrow")
    void replan_at_bedtime_plans_the_next_day() {
        // Dated for tomorrow: today has no forward window left, and a day only takes its own items.
        UUID task = insertTaskDueOn("Work", 0.9, 60, DAY.plusDays(1));
        OffsetDateTime bedtime = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, UTC);

        boolean replanned = service.materializeReplanIfNew(USER, bedtime, UTC);

        assertThat(replanned).isTrue();
        // Nothing is planned for today (its forward window was empty)...
        Integer todayBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_blocks WHERE date_start < ?", Integer.class,
            OffsetDateTime.of(2026, 7, 11, 0, 0, 0, 0, UTC));
        assertThat(todayBlocks).isZero();
        // ...but tomorrow (2026-07-11) is planned as a full day: the task lands within its frontier.
        OffsetDateTime tomorrowBlockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM planner_blocks WHERE executable_id = ?", OffsetDateTime.class, task);
        assertThat(tomorrowBlockStart)
            .isAfterOrEqualTo(OffsetDateTime.of(2026, 7, 11, 7, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("replan excludes an executable completed today even while it keeps a live status")
    void replan_excludes_executable_completed_today() {
        UUID pending = insertTask("Still pending", 0.9, 60);
        // A live (IN_PROGRESS) executable whose completion clock is stamped earlier today: a recurring
        // item checked off this morning must not be re-planned in the afternoon.
        UUID doneToday = insertTask("Done this morning", 0.8, 60);
        stampCompletedAt(doneToday, OffsetDateTime.of(2026, 7, 10, 8, 0, 0, 0, UTC));

        service.generate(USER, DAY, UTC, NOON, true);

        assertThat(scheduledExecutableIds()).contains(pending).doesNotContain(doneToday);
    }

    @Test
    @DisplayName("replan still schedules an executable that was not completed today")
    void replan_schedules_uncompleted_executable() {
        UUID pending = insertTask("Not done", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, true);

        assertThat(scheduledExecutableIds()).contains(pending);
    }

    @Test
    @DisplayName("a habit completed today is dropped today but returns on a future day")
    void habit_completed_today_returns_on_future_days() {
        // A daily habit with no fixed time, checked off this morning: it stays alive (IN_PROGRESS)
        // and its completion clock lands on the target day.
        UUID habit = insertHabit("Daily exercise", 0.9, 45);
        stampCompletedAt(habit, OffsetDateTime.of(2026, 7, 10, 8, 0, 0, 0, UTC));

        // Today's replan must not re-schedule the habit already done today.
        service.generate(USER, DAY, UTC, NOON, true);
        assertThat(scheduledExecutableIds()).doesNotContain(habit);

        // The next day it is due again — the recurrence (DR-04) carries its date forward, which is what
        // makes it a candidate at all — and its completion clock is now before the day, so it returns.
        LocalDate nextDay = DAY.plusDays(1);
        jdbcTemplate.update("UPDATE core_executable SET start_time = ? WHERE id = ?",
            nextDay.atTime(12, 0).atOffset(UTC), habit);
        service.generate(USER, nextDay, UTC,
            OffsetDateTime.of(2026, 7, 11, 12, 0, 0, 0, UTC), false);
        assertThat(scheduledExecutableIds()).contains(habit);
    }

    @Test
    @DisplayName("morning generation is unaffected by completions from prior days")
    void morning_generation_ignores_prior_day_completions() {
        UUID yesterdayDone = insertTask("Done yesterday", 0.9, 60);
        stampCompletedAt(yesterdayDone, OffsetDateTime.of(2026, 7, 9, 20, 0, 0, 0, UTC));

        service.generate(USER, DAY, UTC, NOON, false);

        // A prior-day completion never blocks today's plan.
        assertThat(scheduledExecutableIds()).contains(yesterdayDone);
    }

    @Test
    @DisplayName("the day stops being a wall of blocks: sixteen tasks become a handful of windows")
    void a_full_day_yields_windows_not_a_wall_of_blocks() {
        // The regression test for the failure that motivated ADR-040. In production the old engine
        // turned a day like this into twenty-one flat blocks between 07:00 and 17:30 — a wall nobody
        // executes, so the day gets abandoned and rebuilt by hand, which is exactly the failure the
        // system exists to prevent.
        for (int i = 0; i < 16; i++) {
            insertTask("Work " + i, 0.99 - i * 0.01, 60);
        }

        service.generate(USER, DAY, UTC, NOON, false);

        List<Map<String, Object>> blocks = jdbcTemplate.queryForList(
            "SELECT executable_id, date_start, date_end FROM planner_blocks "
                + "WHERE origin = 'PLANNER' AND status = 'PLANNED' ORDER BY date_start");

        // One block per window, never one per task: the sixteen tasks live INSIDE the day's windows.
        assertThat(blocks).hasSizeLessThan(16);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE container_block_id IS NOT NULL AND type = 'TASK'",
            Integer.class)).isEqualTo(16);

        // The meal anchors still wall — they are commitments on the time axis, which is the one place
        // ADR-040 D1 allows availability to be expressed.
        assertNoBlockOverlaps(blocks,
            OffsetDateTime.of(2026, 7, 10, 12, 30, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 13, 30, 0, 0, UTC));
        assertNoBlockOverlaps(blocks,
            OffsetDateTime.of(2026, 7, 10, 19, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 20, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("nothing trims the tail of the day: the evening is planned like any other band")
    void the_evening_is_no_longer_cut_off() {
        for (int i = 0; i < 16; i++) {
            insertTask("Work " + i, 0.99 - i * 0.01, 60);
        }

        service.generate(USER, DAY, UTC, NOON, false);

        // The retired chaos margin was implemented as a cut to the TAIL of the day: with a 30% margin
        // over 06:00-22:00 the placement limit fell to 17:12, so the afternoon was dead by side effect
        // and not by decision. Daniel's 2026-08-07 directive reopens the evening bands on top of that.
        OffsetDateTime latestEnd = jdbcTemplate.queryForObject(
            "SELECT max(date_end) FROM planner_blocks WHERE origin = 'PLANNER'", OffsetDateTime.class);
        assertThat(latestEnd).isAfter(OffsetDateTime.of(2026, 7, 10, 17, 12, 0, 0, UTC));
    }

    @Test
    @DisplayName("two tasks share a window instead of being glued into two back-to-back blocks")
    void two_tasks_share_a_window() {
        UUID high = insertTask("High", 0.9, 60);
        UUID low = insertTask("Low", 0.3, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        // The five-minute cushion that used to separate two blocks is gone with the other capacity
        // discounts (ADR-040 D1) — and it needs no successor, because the two tasks are no longer two
        // blocks at all. The tail of the window itself is the transition.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, high)).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, low)).isNotNull();
    }

    private static void assertNoBlockOverlaps(List<Map<String, Object>> blocks,
                                              OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        assertThat(blocks).allSatisfy(row -> {
            OffsetDateTime start = toOffsetDateTime(row.get("date_start"));
            OffsetDateTime end = toOffsetDateTime(row.get("date_end"));
            assertThat(start.isBefore(windowEnd) && end.isAfter(windowStart))
                .as("block %s–%s must not overlap %s–%s", start, end, windowStart, windowEnd)
                .isFalse();
        });
    }

    private static long minutesBetween(Map<String, Object> row) {
        return java.time.Duration.between(
            toOffsetDateTime(row.get("date_start")), toOffsetDateTime(row.get("date_end"))).toMinutes();
    }

    private static OffsetDateTime toOffsetDateTime(Object timestamp) {
        return ((java.sql.Timestamp) timestamp).toInstant().atOffset(UTC);
    }

    // ─── fixtures ──────────────────────────────────────────────────────────────

    private List<UUID> scheduledExecutableIds() {
        return jdbcTemplate.queryForList(
            "SELECT executable_id FROM planner_blocks WHERE origin = 'PLANNER' AND status = 'PLANNED'",
            UUID.class);
    }

    private void stampCompletedAt(UUID executableId, OffsetDateTime instant) {
        jdbcTemplate.update(
            "UPDATE core_executable SET last_completed_at = ?, status = 'IN_PROGRESS' WHERE id = ?",
            instant, executableId);
    }

    private UUID insertHabit(String name, double priority, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, priority_score, frequency, start_time)
            VALUES (?, ?, ?, 'HABIT', 'IN_PROGRESS', ?, 1, ?)
            """, id, USER, name, priority, DAY.atTime(12, 0).atOffset(UTC));
        insertProfile(id, estimatedMinutes);
        return id;
    }

    private UUID blockId(UUID executableId) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM planner_blocks WHERE executable_id = ? AND origin = 'PLANNER' "
                + "AND status = 'PLANNED'", UUID.class, executableId);
    }

    /**
     * A task dated FOR the day under test — which is what makes it a candidate at all: admission takes
     * the day's own items and nothing else (ADR-040 D3, as amended). DR-01 puts a TASK's due date in
     * {@code start_time}; the hour is irrelevant, since the due instant scopes the day and never seeds
     * where inside it the work lands.
     */
    private UUID insertTask(String name, double priority, int estimatedMinutes) {
        return insertTaskDueOn(name, priority, estimatedMinutes, DAY);
    }

    /** An undated task: inventory, never a candidate — the admission rule leaves it out of every day. */
    private UUID insertUndatedTask(String name, double priority, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, priority);
        insertProfile(id, estimatedMinutes);
        return id;
    }

    private UUID insertTaskDueOn(String name, double priority, int estimatedMinutes, LocalDate dueDay) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score, start_time)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?, ?)
            """, id, USER, name, priority, dueDay.atTime(12, 0).atOffset(UTC));
        insertProfile(id, estimatedMinutes);
        return id;
    }

    private void insertProfile(UUID executableId, int estimatedMinutes) {
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, executableId, estimatedMinutes);
    }

    private UUID insertTaskDueAt(String name, double priority, int estimatedMinutes,
                                 OffsetDateTime dueInstant) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score, end_time)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?, ?)
            """, id, USER, name, priority, dueInstant);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
    }

    private UUID insertLeadMeasure(String name, UUID cycleId, double priority) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status, priority_score)
            VALUES (?, ?, ?, ?, 'LEAD_MEASURE', 'TODO', ?)
            """, id, USER, cycleId, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, 30)
            """, id);
        return id;
    }

    /** A block as Daniel's own arrives from Notion: no origin, and the initial status of any executable. */
    private UUID insertHandMadeBlock(String name, OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', 'TODO', ?, ?)
            """, id, USER, name, start, end);
        return id;
    }

    private void contain(UUID blockId, UUID memberId) {
        jdbcTemplate.update(
            "UPDATE core_executable SET container_block_id = ?, container_ord = 0 WHERE id = ?",
            blockId, memberId);
    }

    private void insertTimeBlockExecutable(String name, OffsetDateTime start, OffsetDateTime end) {
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, ?, 'TIME_BLOCK', 'PLANNED', 'USER', ?, ?)
            """, UUID.randomUUID(), USER, name, start, end);
    }

    /** A commitment of the user's that owns its own hour, as it arrives from Notion or the calendar. */
    private void insertActivity(String name, OffsetDateTime start, OffsetDateTime end) {
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, 'ACTIVITY', 'TODO', ?, ?)
            """, UUID.randomUUID(), USER, name, start, end);
    }

    /** An open commitment of the given kind: still to be done, and holding its own window. */
    private UUID insertCommitment(String name, String type, OffsetDateTime start, OffsetDateTime end) {
        return insertCommitment(name, type, "TODO", start, end);
    }

    private UUID insertCommitment(String name, String type, String status,
                                  OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, USER, name, type, status, start, end);
        return id;
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, LocalTime.of(hour, minute), UTC);
    }

    private void insertAgendaEvent(String name, OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time)
            VALUES (?, ?, ?, 'AGENDA', 'TODO', ?, ?)
            """, id, USER, name, start, end);
    }

    private UUID insertCycle(String type, String status, LocalDate startDate, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, start_date, end_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, USER, type + "-cycle", type, status, startDate, endDate);
        return id;
    }

    private UUID insertChildCycle(String type, String status, UUID parentCycleId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, parent_cycle_id, name, type, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, USER, parentCycleId, type + "-child", type, status);
        return id;
    }

    private UUID insertRootExecutableWithProgress(UUID cycleId, double progress, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, cycle_id, name, type, status, progress)
            VALUES (?, ?, ?, 'Progressing root', 'TASK', 'IN_PROGRESS', ?)
            """, id, USER, cycleId, progress);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
    }

    private void insertPlannerBlock(UUID executableId, OffsetDateTime start) {
        UUID blockId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, origin, start_time, end_time)
            VALUES (?, ?, 'Settled block', 'TIME_BLOCK', 'DONE', 'PLANNER', ?, ?)
            """, blockId, USER, start, start.plusMinutes(45));
        jdbcTemplate.update(
            "UPDATE core_executable SET container_block_id = ?, container_ord = 0 WHERE id = ?",
            blockId, executableId);
    }

    private void insertSleepRecord(OffsetDateTime bedtime, OffsetDateTime wake, Integer sleepScore,
                                   OffsetDateTime collectedAt) {
        jdbcTemplate.update("""
            INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, collected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), USER, bedtime, wake, sleepScore, collectedAt);
    }
}
