package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyTier;
import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgendaGenerator (#6a, deterministic materialization)")
class AgendaGeneratorTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);

    private final AgendaGenerator generator = new AgendaGenerator(PlannerConstraints.DEFAULT);

    private static final EnergyProfile NEUTRAL = new EnergyProfile(
        EnergyTier.NEUTRAL, 0.25, 3, "Sleep Score → margin 25% → quota 3");

    @Test
    @DisplayName("ranked executables are placed highest-priority first inside the window")
    void places_ranked_executables_in_order() {
        SchedulableExecutable high = task(0.9, 60);
        SchedulableExecutable low = task(0.3, 60);
        PlannerDayState state = state(List.of(high, low), List.of(), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).hasSize(2);
        assertThat(agenda.blocks().get(0).executableId()).isEqualTo(high.id());
        assertThat(agenda.blocks().get(0).start()).isEqualTo(WAKE);
        assertThat(agenda.blocks().get(1).executableId()).isEqualTo(low.id());
    }

    @Test
    @DisplayName("wall: an occupied interval is never scheduled over; the block lands after it")
    void does_not_schedule_over_occupied() {
        SchedulableExecutable executable = task(0.9, 60);
        OccupiedInterval busy = new OccupiedInterval(UUID.randomUUID(),
            WAKE, WAKE.plusMinutes(90), false);
        PlannerDayState state = state(List.of(executable), List.of(), List.of(busy), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).hasSize(1);
        assertThat(agenda.blocks().get(0).start()).isEqualTo(WAKE.plusMinutes(90));
    }

    @Test
    @DisplayName("wall: a read-only AGENDA executable is excluded, never scheduled (ADR-009)")
    void excludes_read_only_agenda() {
        SchedulableExecutable agendaExecutable = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.AGENDA, 0.9, false, null, null, 0, 30, 0, null, null);
        PlannerDayState state =
            state(List.of(agendaExecutable), List.of(), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).isEmpty();
        assertThat(agenda.excluded())
            .containsExactly(new ExcludedExecutable(agendaExecutable.id(), ExclusionReason.READ_ONLY_AGENDA));
    }

    @Test
    @DisplayName("F1: the WIG lead measure is reserved first, at the window start, ahead of the ranking")
    void reserves_wig_first() {
        SchedulableExecutable ranked = task(0.99, 60);
        UUID leadMeasureId = UUID.randomUUID();
        MciWig wig = mci(leadMeasureId, 0.2, 0.5);
        PlannerDayState state =
            state(List.of(ranked), List.of(wig), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        AgendaBlock first = agenda.blocks().get(0);
        assertThat(first.executableId()).isEqualTo(leadMeasureId);
        assertThat(first.wig()).isTrue();
        assertThat(first.start()).isEqualTo(WAKE);
        assertThat(first.durationMinutes()).isEqualTo(45);
    }

    @Test
    @DisplayName("F1: every active MCI with a lead measure gets a block — the portfolio, not one WIG")
    void reserves_a_block_per_active_mci() {
        UUID leadA = UUID.randomUUID();
        UUID leadB = UUID.randomUUID();
        MciWig a = mci(leadA, 0.2, 0.5);
        MciWig b = mci(leadB, 0.6, 0.5);
        PlannerDayState state = state(List.of(), List.of(a, b), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).filteredOn(AgendaBlock::wig).extracting(AgendaBlock::executableId)
            .containsExactlyInAnyOrder(leadA, leadB);
    }

    @Test
    @DisplayName("F1 defect (a): an MCI with no lead measure is excluded with an alert, never NaN")
    void mci_without_lead_measure_is_excluded_with_alert() {
        UUID mciId = UUID.randomUUID();
        MciWig noLead = new MciWig(mciId, null, 0.0, 1.0, false, null, false, 0);
        PlannerDayState state = state(List.of(), List.of(noLead), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).isEmpty();
        assertThat(agenda.excluded())
            .containsExactly(new ExcludedExecutable(mciId, ExclusionReason.WIG_WITHOUT_LEAD_MEASURE));
    }

    @Test
    @DisplayName("F6: a high-load WIG consumes the whole quota and is intocable; high-load urgents trim")
    void high_load_quota_never_trims_wig() {
        EnergyProfile quotaOne = new EnergyProfile(EnergyTier.LOW, 0.0, 1, "quota 1");
        UUID leadMeasureId = UUID.randomUUID();
        MciWig wig = mci(leadMeasureId, 0.5, 0.5);
        // The WIG's lead measure is itself high-load and consumes the single quota slot; it is never
        // trimmed. Both high-load ranked urgents are then excluded by the quota.
        SchedulableExecutable wigProfile = highLoadWithId(leadMeasureId, 0.4, 45);
        SchedulableExecutable h1 = highLoad(0.9, 60);
        SchedulableExecutable h2 = highLoad(0.8, 60);
        PlannerDayState state =
            state(List.of(wigProfile, h1, h2), List.of(wig), List.of(), quotaOne, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).anyMatch(AgendaBlock::wig);
        assertThat(agenda.blocks()).filteredOn(b -> !b.wig() && b.highLoad()).isEmpty();
        assertThat(agenda.excluded())
            .contains(new ExcludedExecutable(h1.id(), ExclusionReason.HIGH_LOAD_QUOTA_EXCEEDED))
            .contains(new ExcludedExecutable(h2.id(), ExclusionReason.HIGH_LOAD_QUOTA_EXCEEDED));
    }

    @Test
    @DisplayName("F3: the chaos margin holds back part of the window from the ranking")
    void chaos_margin_reserves_slack() {
        // 16h window (960 min). A 50% margin leaves 480 usable minutes.
        EnergyProfile halfMargin = new EnergyProfile(EnergyTier.LOW, 0.5, 3, "margin 50%");
        SchedulableExecutable a = task(0.9, 300);
        SchedulableExecutable b = task(0.8, 300); // 300 + 300 = 600 > 480 usable
        PlannerDayState state = state(List.of(a, b), List.of(), List.of(), halfMargin, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).hasSize(1);
        assertThat(agenda.excluded())
            .contains(new ExcludedExecutable(b.id(), ExclusionReason.NO_ROOM_IN_WINDOW));
    }

    @Test
    @DisplayName("F5 degraded: on incomplete data only the WIG plus a few urgents are scheduled")
    void degraded_schedules_wig_plus_urgents() {
        MciWig wig = mci(UUID.randomUUID(), 0.3, 0.5);
        // Five ranked tasks; degradedUrgentCount default is 2.
        List<SchedulableExecutable> ranked = List.of(
            task(0.9, 30), task(0.8, 30), task(0.7, 30), task(0.6, 30), task(0.5, 30));
        PlannerDayState state = state(ranked, List.of(wig), List.of(), NEUTRAL, false);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.degraded()).isTrue();
        assertThat(agenda.blocks()).anyMatch(AgendaBlock::wig);
        // WIG + 2 urgents = 3 blocks; the remaining 3 ranked are excluded for no room.
        assertThat(agenda.blocks()).hasSize(3);
        assertThat(agenda.excluded()).filteredOn(e -> e.reason() == ExclusionReason.NO_ROOM_IN_WINDOW)
            .hasSize(3);
    }

    @Test
    @DisplayName("F1 degraded reserve: LOW quota 2 + 2 MCIs + high-load urgents → 2 WIGs, 0 high-load urgents")
    void degraded_reserve_fills_quota_with_wigs_intocable() {
        // LOW day: high-load quota 2. Two active MCIs each carry a high-load lead measure; the WIGs
        // consume the whole quota, so no high-load urgent gets a slot — and the WIGs are never trimmed.
        EnergyProfile lowQuotaTwo = new EnergyProfile(EnergyTier.LOW, 0.0, 2, "LOW quota 2");
        UUID leadA = UUID.randomUUID();
        UUID leadB = UUID.randomUUID();
        MciWig a = mci(leadA, 0.1, 0.5);
        MciWig b = mci(leadB, 0.6, 0.5);
        // Lead measures profiled high-load so they count against the quota.
        SchedulableExecutable leadProfileA = highLoadWithId(leadA, 0.4, 45);
        SchedulableExecutable leadProfileB = highLoadWithId(leadB, 0.4, 45);
        SchedulableExecutable urgent1 = highLoad(0.99, 60);
        SchedulableExecutable urgent2 = highLoad(0.98, 60);
        PlannerDayState state = state(
            List.of(leadProfileA, leadProfileB, urgent1, urgent2), List.of(a, b), List.of(),
            lowQuotaTwo, true);

        Agenda agenda = generator.generate(state);

        // Both WIGs present and flagged wig; both high-load urgents excluded by the quota.
        assertThat(agenda.blocks()).filteredOn(AgendaBlock::wig).extracting(AgendaBlock::executableId)
            .containsExactlyInAnyOrder(leadA, leadB);
        assertThat(agenda.blocks()).filteredOn(bk -> !bk.wig() && bk.highLoad()).isEmpty();
        assertThat(agenda.excluded())
            .contains(new ExcludedExecutable(urgent1.id(), ExclusionReason.HIGH_LOAD_QUOTA_EXCEEDED))
            .contains(new ExcludedExecutable(urgent2.id(), ExclusionReason.HIGH_LOAD_QUOTA_EXCEEDED));
    }

    @Test
    @DisplayName("paused: an IN_PROGRESS executable that gets no block is listed explicitly")
    void lists_paused_in_progress() {
        SchedulableExecutable inProgressNoRoom = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.TASK, 0.5, true, null, null, 0, 0, 0, null, null); // zero effort
        PlannerDayState state =
            state(List.of(inProgressNoRoom), List.of(), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.blocks()).isEmpty();
        assertThat(agenda.paused()).containsExactly(inProgressNoRoom.id());
    }

    @Test
    @DisplayName("legibility: the energy criterion is surfaced and every block carries a reason")
    void surfaces_energy_criterion_and_reasons() {
        SchedulableExecutable executable = task(0.9, 60);
        PlannerDayState state = state(List.of(executable), List.of(), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.energyCriterion()).isEqualTo(NEUTRAL.criterion());
        assertThat(agenda.blocks()).allSatisfy(b -> assertThat(b.reason()).isNotBlank());
    }

    @Test
    @DisplayName("no remaining effort: a fully-settled task (estimated=0) is excluded with NO_REMAINING_EFFORT")
    void excludes_zero_effort() {
        // estimated=0 means fully overrun; the calculator returns 0 → the generator excludes it.
        SchedulableExecutable zero = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.TASK, 0.9, false, null, null, 0, 0, 0, null, null);
        PlannerDayState state = state(List.of(zero), List.of(), List.of(), NEUTRAL, true);

        Agenda agenda = generator.generate(state);

        assertThat(agenda.excluded())
            .containsExactly(new ExcludedExecutable(zero.id(), ExclusionReason.NO_REMAINING_EFFORT));
    }

    @Test
    @DisplayName("pinned start: a task with dueInstant inside the window starts exactly at dueInstant")
    void pinned_start_within_window_anchors_block_start() {
        // Given: a task whose reminder time is 15:00 (well inside the window) with 60 min of effort.
        // The reminder time is when to START, so the block runs 15:00-16:00.
        OffsetDateTime due = OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, ZoneOffset.UTC);
        SchedulableExecutable executable = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.TASK, 0.9, false, null, null, 0, 60, 0, due, null);
        PlannerDayState state = state(List.of(executable), List.of(), List.of(), NEUTRAL, true);

        // When
        Agenda agenda = generator.generate(state);

        // Then: exactly one block, starting at the reminder time and ending 60 min later
        assertThat(agenda.blocks()).hasSize(1);
        AgendaBlock block = agenda.blocks().get(0);
        assertThat(block.start()).isEqualTo(due);
        assertThat(block.end()).isEqualTo(due.plusMinutes(60));
    }

    @Test
    @DisplayName("pinned start: a dueInstant outside the window falls back to cursor placement")
    void pinned_start_outside_window_falls_back_to_cursor() {
        // Given: a task due at midnight (00:00 on targetDay) — before the 07:00 window start
        OffsetDateTime midnight = OffsetDateTime.of(2026, 7, 10, 0, 0, 0, 0, ZoneOffset.UTC);
        SchedulableExecutable executable = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.TASK, 0.9, false, null, null, 0, 60, 0, midnight, null);
        PlannerDayState state = state(List.of(executable), List.of(), List.of(), NEUTRAL, true);

        // When
        Agenda agenda = generator.generate(state);

        // Then: the task is still placed (cursor fallback), but not starting at midnight
        assertThat(agenda.blocks()).hasSize(1);
        AgendaBlock block = agenda.blocks().get(0);
        assertThat(block.start()).isNotEqualTo(midnight);
        assertThat(block.start()).isAfterOrEqualTo(WAKE);
    }

    @Test
    @DisplayName("pinned start: a reminder whose block would run past bedtime falls back to cursor")
    void pinned_start_past_bedtime_falls_back_to_cursor() {
        // Given: a task whose reminder is 22:30, 60 min of effort → 22:30-23:30 would spill past the
        // 23:00 bedtime, so the pinned placement is rejected and the cursor fallback keeps it in-window.
        OffsetDateTime due = BEDTIME.minusMinutes(30); // 22:30 UTC
        SchedulableExecutable executable = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.TASK, 0.9, false, null, null, 0, 60, 0, due, null);
        PlannerDayState state = state(List.of(executable), List.of(), List.of(), NEUTRAL, true);

        // When
        Agenda agenda = generator.generate(state);

        // Then: the task is still placed (cursor fallback) and never runs past bedtime
        assertThat(agenda.blocks()).hasSize(1);
        AgendaBlock block = agenda.blocks().get(0);
        assertThat(block.start()).isNotEqualTo(due);
        assertThat(block.end()).isBeforeOrEqualTo(BEDTIME);
    }

    private static PlannerDayState state(
        List<SchedulableExecutable> ranked, List<MciWig> wigs,
        List<OccupiedInterval> occupied, EnergyProfile energy, boolean dataComplete) {
        return new PlannerDayState(WAKE, BEDTIME, ranked, wigs, occupied, energy, dataComplete);
    }

    private static MciWig mci(UUID leadMeasureId, double aggregatedProgress, double remainingFraction) {
        return new MciWig(UUID.randomUUID(), leadMeasureId, aggregatedProgress, remainingFraction,
            false, LocalDate.of(2026, 7, 31), false, 0);
    }

    private static SchedulableExecutable task(double priority, int estimatedMinutes) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, null,
            null, 0, estimatedMinutes, 0, null, null);
    }

    private static SchedulableExecutable highLoad(double priority, int estimatedMinutes) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, 5,
            null, 0, estimatedMinutes, 0, null, null);
    }

    private static SchedulableExecutable highLoadWithId(UUID id, double priority, int estimatedMinutes) {
        return new SchedulableExecutable(id, ExecutableType.LEAD_MEASURE, priority, false, 5,
            null, 0, estimatedMinutes, 0, null, null);
    }
}
