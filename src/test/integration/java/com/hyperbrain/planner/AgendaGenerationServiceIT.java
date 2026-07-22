package com.hyperbrain.planner;

import com.hyperbrain.planner.application.AgendaGenerationService;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    @BeforeEach
    void cleanState() throws Exception {
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
            "SELECT executable_id, status, origin FROM core_time_block ORDER BY date_start");
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
            "SELECT date_start FROM core_time_block WHERE executable_id = ?",
            OffsetDateTime.class, task);
        // The block cannot start before the AGENDA wall ends at 09:00.
        assertThat(blockStart).isAfterOrEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC));
        // And the AGENDA executable itself is never given a block.
        Integer agendaBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block b JOIN core_executable e ON e.id = b.executable_id "
                + "WHERE e.type = 'AGENDA'", Integer.class);
        assertThat(agendaBlocks).isZero();
    }

    @Test
    @DisplayName("F1: the WIG lead measure of the active MCI is reserved first")
    void reserves_wig_first() {
        UUID mci = insertCycle("MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID wig = insertLeadMeasure("Predictive action", mci, 0.5);
        insertTask("Whirlwind", 0.99, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        // The earliest block belongs to the WIG lead measure, ahead of the higher-scored task.
        UUID firstBlockExecutable = jdbcTemplate.queryForObject(
            "SELECT executable_id FROM core_time_block ORDER BY date_start LIMIT 1", UUID.class);
        assertThat(firstBlockExecutable).isEqualTo(wig);
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
            "SELECT executable_id FROM core_time_block WHERE executable_id IN (?, ?)",
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
            "SELECT date_start FROM core_time_block WHERE executable_id = ?",
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
            "SELECT date_start FROM core_time_block WHERE executable_id = ?",
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
            "SELECT count(*) FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED'",
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
            "SELECT count(*) FROM core_time_block WHERE executable_id = ?", Integer.class, low);
        assertThat(lowBlocks).isZero();

        // And the staged write-back carries the removed block id so its Apple EKEvent is deleted.
        String payload = jdbcTemplate.queryForObject(
            "SELECT payload FROM outbox_events WHERE aggregate_type = 'AGENDA_BLOCK'", String.class);
        assertThat(payload).contains(lowBlockFirst.toString());
    }

    @Test
    @DisplayName("reconciliation: a task added on the replan is inserted while the prior block keeps its id")
    void regeneration_inserts_new_block_without_disturbing_survivors() {
        UUID high = insertTask("High", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);
        UUID highBlockFirst = blockId(high);
        jdbcTemplate.update("DELETE FROM outbox_events");

        UUID added = insertTask("Added", 0.5, 60);
        service.generate(USER, DAY, UTC, NOON, false);

        // The prior block is untouched (same id); the new task gets its own fresh block.
        assertThat(blockId(high)).isEqualTo(highBlockFirst);
        assertThat(blockId(added)).isNotNull().isNotEqualTo(highBlockFirst);
        // Nothing was removed, so the staged event carries an empty removed list.
        Integer removedCount = jdbcTemplate.queryForObject(
            "SELECT jsonb_array_length(payload -> 'removed_block_ids') FROM outbox_events "
                + "WHERE aggregate_type = 'AGENDA_BLOCK'", Integer.class);
        assertThat(removedCount).isZero();
    }

    @Test
    @DisplayName("idempotent convergence: a FOCUS/USER block on the same day survives regeneration")
    void regeneration_preserves_non_planner_blocks() {
        UUID task = insertTask("High", 0.9, 60);
        // A USER-origin block on the same day must not be cleared by the planner delete.
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin)
            VALUES (?, ?, ?, ?, 'PLANNED', 'USER')
            """, UUID.randomUUID(), task,
            OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 16, 0, 0, 0, UTC));

        service.generate(USER, DAY, UTC, NOON, false);

        Integer userBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE origin = 'USER'", Integer.class);
        assertThat(userBlocks).isEqualTo(1);
    }

    @Test
    @DisplayName("write-back staging: a planned day stages one AgendaBlockPlannedEvent on the outbox")
    void planned_day_stages_agenda_block_event() {
        insertTask("High", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        Integer events = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'AGENDA_BLOCK' "
                + "AND event_type = 'AgendaBlockPlannedEvent'", Integer.class);
        assertThat(events).isEqualTo(1);
        String aggregateId = jdbcTemplate.queryForObject(
            "SELECT aggregate_id FROM outbox_events WHERE aggregate_type = 'AGENDA_BLOCK'", String.class);
        assertThat(aggregateId).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("replan-from-now: no block is scheduled before the reference instant")
    void replan_respects_now() {
        insertTask("Afternoon work", 0.9, 60);

        service.generate(USER, DAY, UTC, NOON, true);

        OffsetDateTime blockStart = jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM core_time_block", OffsetDateTime.class);
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
            insertTask("Task " + i, 0.99 - i * 0.02, 60);
        }
        // 2026-07-22T09:23:36Z = 2026-07-22 04:23 America/Bogota → today = 07-22.
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 22, 9, 23, 36, 0, UTC);

        service.materializeReplanIfNew(USER, occurredAt, bogota);

        int todayBlocks = localDayCount(bogota, 2026, 7, 22);
        int tomorrowBlocks = localDayCount(bogota, 2026, 7, 23);
        // Today gets the bulk (its full window lies ahead), tomorrow only the overflow.
        assertThat(todayBlocks).isGreaterThan(tomorrowBlocks);
        // The first block opens at the local wake (07:00 Bogota), never in the pre-wake hours.
        OffsetDateTime firstStartLocal = jdbcTemplate.queryForObject(
            "SELECT min(date_start) FROM core_time_block WHERE origin = 'PLANNER'", OffsetDateTime.class)
            .atZoneSameInstant(bogota).toOffsetDateTime();
        assertThat(firstStartLocal.toLocalTime()).isEqualTo(java.time.LocalTime.of(7, 0));
        assertThat(firstStartLocal.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 22));
    }

    private int localDayCount(java.time.ZoneId zone, int year, int month, int day) {
        OffsetDateTime start = java.time.LocalDate.of(year, month, day).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = start.plusDays(1);
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED' "
                + "AND date_start >= ? AND date_start < ?", Integer.class, start, end);
        return n == null ? 0 : n;
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
            "SELECT count(*) FROM core_time_block", Integer.class);
        assertThat(todayBlocks).isZero();
    }

    @Test
    @DisplayName("end-of-day replan plans the NEXT day: the 48h window skips today's empty window and "
        + "materializes tomorrow")
    void replan_at_bedtime_plans_the_next_day() {
        UUID task = insertTask("Work", 0.9, 60);
        OffsetDateTime bedtime = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, UTC);

        boolean replanned = service.materializeReplanIfNew(USER, bedtime, UTC);

        assertThat(replanned).isTrue();
        // Nothing is planned for today (its forward window was empty)...
        Integer todayBlocks = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE date_start < ?", Integer.class,
            OffsetDateTime.of(2026, 7, 11, 0, 0, 0, 0, UTC));
        assertThat(todayBlocks).isZero();
        // ...but tomorrow (2026-07-11) is planned as a full day: the task lands within its frontier.
        OffsetDateTime tomorrowBlockStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block WHERE executable_id = ?", OffsetDateTime.class, task);
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

        // The next day it is due again: its completion clock is now before the day, so it returns.
        LocalDate nextDay = DAY.plusDays(1);
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
    @DisplayName("H1 humanized floor: buffers, meal protection, no slivers and an occupancy cap hold together")
    void humanized_floor_invariants_hold_together() {
        // A full day of work plus a sliver and a reminder-pinned task, over the cold-start 07:00–23:00
        // window. The humanized floor must space, protect meals, drop the sliver, cap occupancy — and
        // still honor the recent pinned-start sanitation (#12).
        for (int i = 0; i < 16; i++) {
            insertTask("Work " + i, 0.99 - i * 0.01, 60);
        }
        UUID sliver = insertTask("Sliver", 0.95, 10);
        // Highest priority so it is anchored first, before the cursor fill can reach 15:00.
        UUID pinned = insertTaskDueAt("Reminder at 15:00", 0.999, 60,
            OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, UTC));

        service.generate(USER, DAY, UTC, NOON, false);

        List<Map<String, Object>> blocks = jdbcTemplate.queryForList(
            "SELECT executable_id, date_start, date_end FROM core_time_block "
                + "WHERE origin = 'PLANNER' AND status = 'PLANNED' ORDER BY date_start");

        // Rule 2 — no block invades either protected meal window (12:30–13:30, 19:00–20:00 UTC).
        assertNoBlockOverlaps(blocks,
            OffsetDateTime.of(2026, 7, 10, 12, 30, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 13, 30, 0, 0, UTC));
        assertNoBlockOverlaps(blocks,
            OffsetDateTime.of(2026, 7, 10, 19, 0, 0, 0, UTC),
            OffsetDateTime.of(2026, 7, 10, 20, 0, 0, 0, UTC));

        // Rule 3 — no sub-minimum sliver survives; the 10-min task is left unscheduled.
        assertThat(blocks).allSatisfy(row ->
            assertThat(minutesBetween(row)).isGreaterThanOrEqualTo(15));
        assertThat(blocks).noneMatch(row -> sliver.equals(row.get("executable_id")));

        // Rule 6 — the day is never packed past the 85% occupancy cap of the 960-min window.
        long busy = blocks.stream().mapToLong(AgendaGenerationServiceIT::minutesBetween).sum();
        assertThat(busy).isLessThanOrEqualTo(Math.round(960 * 0.85));

        // #12 — the reminder-pinned task still starts exactly at its reminder instant.
        OffsetDateTime pinnedStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block WHERE executable_id = ?", OffsetDateTime.class, pinned);
        assertThat(pinnedStart).isEqualTo(OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, UTC));
    }

    @Test
    @DisplayName("H1 rule 1: consecutive cursor-placed blocks are spaced by the transition buffer")
    void consecutive_blocks_carry_a_transition_buffer() {
        insertTask("High", 0.9, 60);
        insertTask("Low", 0.3, 60);

        service.generate(USER, DAY, UTC, NOON, false);

        List<OffsetDateTime> bounds = jdbcTemplate.queryForList(
            "SELECT date_end FROM core_time_block ORDER BY date_start", OffsetDateTime.class);
        OffsetDateTime firstEnd = bounds.get(0);
        OffsetDateTime secondStart = jdbcTemplate.queryForObject(
            "SELECT date_start FROM core_time_block ORDER BY date_start OFFSET 1 LIMIT 1",
            OffsetDateTime.class);
        // A 5-min transition buffer separates the two blocks (07:00–08:00 then 08:05–09:05).
        assertThat(java.time.Duration.between(firstEnd, secondStart).toMinutes()).isEqualTo(5);
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
            "SELECT executable_id FROM core_time_block WHERE origin = 'PLANNER' AND status = 'PLANNED'",
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
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score, frequency)
            VALUES (?, ?, ?, 'HABIT', 'IN_PROGRESS', ?, 1)
            """, id, USER, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
    }

    private UUID blockId(UUID executableId) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM core_time_block WHERE executable_id = ? AND origin = 'PLANNER' "
                + "AND status = 'PLANNED'", UUID.class, executableId);
    }

    private UUID insertTask(String name, double priority, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
        return id;
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
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin)
            VALUES (?, ?, ?, ?, 'SETTLED', 'PLANNER')
            """, UUID.randomUUID(), executableId, start, start.plusMinutes(45));
    }

    private void insertSleepRecord(OffsetDateTime bedtime, OffsetDateTime wake, Integer sleepScore,
                                   OffsetDateTime collectedAt) {
        jdbcTemplate.update("""
            INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, collected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), USER, bedtime, wake, sleepScore, collectedAt);
    }
}
