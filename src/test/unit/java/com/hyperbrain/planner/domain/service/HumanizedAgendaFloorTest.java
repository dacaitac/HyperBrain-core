package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyTier;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SlotPurpose;
import com.hyperbrain.planner.domain.model.TemplateSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The floor pipeline after ADR-040: batch by context, then fill the windows. The post-placement stage
 * this used to end with is gone — dropping slivers and trimming to an occupancy cap were two of the
 * four capacity discounts D1 retired.
 */
@DisplayName("HumanizedAgendaFloor — batch, then fill the day's windows (ADR-040 D6)")
class HumanizedAgendaFloorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 7);
    private static final EnergyProfile NEUTRAL =
        new EnergyProfile(EnergyTier.NEUTRAL, 3, "neutral");

    @Test
    @DisplayName("the degraded day comes out laid and ordered, but neither grouped nor named")
    void the_degraded_day_is_laid_and_ordered_but_unnamed() {
        // Given
        List<SchedulableExecutable> ranked = List.of(task(0.9, null), task(0.5, null));

        // When
        Agenda agenda = floor().generate(state(
            List.of(window("WORK", 9, 11, SlotPurpose.WORK)), ranked));

        // Then: a block exists, its membership is ordered by rank, and it carries no name at all —
        // grouping and naming are the intelligent layer's work.
        assertThat(agenda.blocks()).singleElement().satisfies(block -> {
            assertThat(block.members()).containsExactly(ranked.get(0).id(), ranked.get(1).id());
            assertThat(block.theme()).isNull();
            assertThat(block.reason()).isNotBlank();
        });
        assertThat(agenda.degraded()).isFalse();
    }

    @Test
    @DisplayName("context batching keeps same-cycle work together inside a comparable-priority band")
    void context_batching_groups_comparable_work() {
        // Given: two cycles interleaved by score, all inside one 0.10 band.
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        List<SchedulableExecutable> ranked = List.of(
            task(0.90, alpha), task(0.88, beta), task(0.86, alpha), task(0.84, beta));

        // When: two windows, so the batching decides which pair shares a window.
        Agenda agenda = floor().generate(state(List.of(
            window("MORNING", 9, 11, SlotPurpose.WORK),
            window("AFTERNOON", 14, 16, SlotPurpose.WHIRLWIND)), ranked));

        // Then: each window holds one cycle's work rather than a slice of both.
        List<UUID> first = agenda.blocks().get(0).members();
        assertThat(first).hasSize(2);
        assertThat(cycleOf(ranked, first.get(0))).isEqualTo(cycleOf(ranked, first.get(1)));
    }

    @Test
    @DisplayName("the floor rejects a null state instead of planning an empty day by accident")
    void a_null_state_is_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> floor().generate(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static HumanizedAgendaFloor floor() {
        HumanizationSettings settings = new HumanizationSettings(List.of(), 0.10);
        return new HumanizedAgendaFloor(
            new ContextBatcher(), new AgendaGenerator(PlannerConstraints.DEFAULT), settings);
    }

    private static UUID cycleOf(List<SchedulableExecutable> ranked, UUID id) {
        return ranked.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().cycleId();
    }

    private static PlannerDayState state(List<DayWindow> windows,
                                         List<SchedulableExecutable> ranked) {
        return new PlannerDayState(
            at(7), at(22), windows, java.util.Map.of(), ranked, List.of(), List.of(), java.util.Set.of(),
            NEUTRAL, true);
    }

    private static DayWindow window(String slotId, int startHour, int endHour, SlotPurpose purpose) {
        return new DayWindow(
            new TemplateSlot(slotId, slotId, startHour * 60, endHour * 60, purpose), at(startHour), at(endHour));
    }

    private static SchedulableExecutable task(double priority, UUID cycleId) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, null, 0, 60, null, cycleId);
    }

    private static OffsetDateTime at(int hour) {
        return DAY.atTime(hour, 0).atOffset(ZoneOffset.UTC);
    }
}
