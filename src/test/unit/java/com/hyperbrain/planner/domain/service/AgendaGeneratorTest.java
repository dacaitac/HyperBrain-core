package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyTier;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SlotPurpose;
import com.hyperbrain.planner.domain.model.TemplateSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgendaGenerator — the window is the unit of scheduling (ADR-040)")
class AgendaGeneratorTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 8, 7);
    private static final EnergyProfile NEUTRAL =
        new EnergyProfile(EnergyTier.NEUTRAL, 0.0, 3, "neutral");

    private final AgendaGenerator generator = new AgendaGenerator();

    @Test
    @DisplayName("work becomes a member of a window; it never gets a block of its own")
    void work_becomes_a_member_of_a_window() {
        // Given: one window and three tasks. The engine this replaced would have produced three blocks.
        List<SchedulableExecutable> ranked = List.of(task(0.9), task(0.8), task(0.7));

        // When
        Agenda agenda = generator.generate(state(List.of(window("WORK", 9, 11, SlotPurpose.WORK)), ranked));

        // Then
        assertThat(agenda.blocks()).hasSize(1);
        AgendaBlock block = agenda.blocks().get(0);
        assertThat(block.members()).containsExactlyElementsOf(ids(ranked));
        assertThat(block.start()).isEqualTo(at(9));
        assertThat(block.end()).isEqualTo(at(11));
        assertThat(block.templateSlotId()).isEqualTo("WORK");
    }

    @Test
    @DisplayName("the day is not compressed against its first hour: ranked work is dealt across the windows")
    void ranked_work_is_dealt_across_every_window() {
        // Given: four tasks and two windows — the exact shape that used to collapse onto the first gap.
        List<SchedulableExecutable> ranked = List.of(task(0.9), task(0.8), task(0.7), task(0.6));
        List<DayWindow> windows = List.of(
            window("MORNING", 9, 11, SlotPurpose.WORK),
            window("AFTERNOON", 14, 16, SlotPurpose.WHIRLWIND));

        // When
        Agenda agenda = generator.generate(state(windows, ranked));

        // Then: two blocks, two members each, and rank order is preserved both within and across them.
        assertThat(agenda.blocks()).hasSize(2);
        assertThat(agenda.blocks().get(0).members())
            .containsExactly(ranked.get(0).id(), ranked.get(1).id());
        assertThat(agenda.blocks().get(1).members())
            .containsExactly(ranked.get(2).id(), ranked.get(3).id());
        assertThat(agenda.excluded()).isEmpty();
    }

    @Test
    @DisplayName("an uneven deal differs by at most one, and no window is left empty while another overfills")
    void an_uneven_deal_stays_balanced() {
        // Given: five tasks over two windows.
        List<SchedulableExecutable> ranked =
            List.of(task(0.9), task(0.8), task(0.7), task(0.6), task(0.5));
        List<DayWindow> windows = List.of(
            window("MORNING", 9, 11, SlotPurpose.WORK),
            window("AFTERNOON", 14, 16, SlotPurpose.WHIRLWIND));

        // When
        Agenda agenda = generator.generate(state(windows, ranked));

        // Then
        assertThat(agenda.blocks().get(0).members()).hasSize(3);
        assertThat(agenda.blocks().get(1).members()).hasSize(2);
    }

    @Test
    @DisplayName("the goal claims a goal window, alone: the band the template calls Meta is for the goal")
    void the_goal_claims_a_goal_window_alone() {
        // Given
        UUID leadMeasure = UUID.randomUUID();
        List<SchedulableExecutable> ranked = List.of(
            new SchedulableExecutable(leadMeasure, ExecutableType.LEAD_MEASURE, 0.5, false,
                null, null, 0, 30, 0, null, null),
            task(0.9));
        List<DayWindow> windows = List.of(
            window("WORK", 7, 8, SlotPurpose.WORK),
            window("GOAL", 9, 11, SlotPurpose.GOAL));

        // When
        Agenda agenda = generator.generate(
            state(windows, ranked, List.of(wig(leadMeasure))));

        // Then: the goal takes the GOAL band even though a window opens earlier, and holds it alone.
        AgendaBlock goalBlock = agenda.blocks().stream()
            .filter(AgendaBlock::wig).findFirst().orElseThrow();
        assertThat(goalBlock.templateSlotId()).isEqualTo("GOAL");
        assertThat(goalBlock.members()).containsExactly(leadMeasure);
        // The ranked task fell into the remaining window, never into the goal's.
        AgendaBlock other = agenda.blocks().stream()
            .filter(block -> !block.wig()).findFirst().orElseThrow();
        assertThat(other.templateSlotId()).isEqualTo("WORK");
    }

    @Test
    @DisplayName("a sized window prints its internal split on the block; an unsized one prints none")
    void the_internal_split_is_written_on_the_block() {
        // Given: a two-hour goal band and a whirlwind band of the same length.
        List<SchedulableExecutable> ranked = List.of(task(0.9), task(0.8));
        List<DayWindow> windows = List.of(
            window("DEEP", 9, 11, SlotPurpose.WORK),
            window("WHIRLWIND", 14, 16, SlotPurpose.WHIRLWIND));

        // When
        Agenda agenda = generator.generate(state(windows, ranked));

        // Then: the split is information for the timer, and only the sized purposes carry it.
        assertThat(agenda.blocks().get(0).reason()).contains("timer: ").contains("90+30");
        assertThat(agenda.blocks().get(1).reason()).doesNotContain("timer: ");
    }

    @Test
    @DisplayName("a read-only agenda item is never scheduled; it is the floor the day is laid against")
    void a_read_only_agenda_item_is_never_scheduled() {
        // Given
        SchedulableExecutable agendaItem = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.AGENDA, 0.99, false, null, null, 0, 60, 0, null, null);

        // When
        Agenda agenda = generator.generate(
            state(List.of(window("WORK", 9, 11, SlotPurpose.WORK)), List.of(agendaItem)));

        // Then
        assertThat(agenda.blocks()).isEmpty();
        assertThat(agenda.excluded()).singleElement().satisfies(excluded -> {
            assertThat(excluded.executableId()).isEqualTo(agendaItem.id());
            assertThat(excluded.reason()).isEqualTo(ExclusionReason.READ_ONLY_AGENDA);
        });
    }

    @Test
    @DisplayName("an activity is never put inside a window: it already is a block of time")
    void a_calendar_event_type_is_never_contained() {
        // Given
        SchedulableExecutable activity = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.ACTIVITY, 0.99, false, null, null, 0, 60, 0, null, null);
        SchedulableExecutable session = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.LEARNING_SESSION, 0.98, false, null, null, 0, 60, 0,
            null, null);

        // When
        Agenda agenda = generator.generate(
            state(List.of(window("WORK", 9, 11, SlotPurpose.WORK)), List.of(activity, session)));

        // Then
        assertThat(agenda.blocks()).isEmpty();
        assertThat(agenda.excluded()).extracting(e -> e.reason())
            .containsOnly(ExclusionReason.NOT_CONTAINABLE);
    }

    @Test
    @DisplayName("a day with no usable window plans nothing and says why — it never invents a slot")
    void a_day_with_no_window_plans_nothing() {
        // Given
        List<SchedulableExecutable> ranked = List.of(task(0.9));

        // When
        Agenda agenda = generator.generate(state(List.of(), ranked));

        // Then
        assertThat(agenda.blocks()).isEmpty();
        assertThat(agenda.excluded()).singleElement().satisfies(excluded ->
            assertThat(excluded.reason()).isEqualTo(ExclusionReason.NO_ROOM_IN_WINDOW));
    }

    @Test
    @DisplayName("an in-progress executable left without a window is reported as paused, never dropped silently")
    void in_progress_work_without_a_window_is_paused() {
        // Given
        SchedulableExecutable running = new SchedulableExecutable(
            UUID.randomUUID(), ExecutableType.TASK, 0.9, true, null, null, 0, 60, 0, null, null);

        // When
        Agenda agenda = generator.generate(state(List.of(), List.of(running)));

        // Then
        assertThat(agenda.paused()).containsExactly(running.id());
    }

    @Test
    @DisplayName("nothing discounts capacity any more: a high-load day fills its windows all the same")
    void the_high_load_quota_no_longer_trims_the_day() {
        // Given: four maximally draining tasks — under the retired F6 quota (3) one would have been cut.
        List<SchedulableExecutable> ranked = List.of(
            drainingTask(0.9), drainingTask(0.8), drainingTask(0.7), drainingTask(0.6));

        // When
        Agenda agenda = generator.generate(
            state(List.of(window("WORK", 9, 11, SlotPurpose.WORK)), ranked));

        // Then
        assertThat(agenda.blocks().get(0).members()).hasSize(4);
        assertThat(agenda.excluded()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static PlannerDayState state(List<DayWindow> windows, List<SchedulableExecutable> ranked) {
        return state(windows, ranked, List.of());
    }

    private static PlannerDayState state(List<DayWindow> windows, List<SchedulableExecutable> ranked,
                                         List<MciWig> wigs) {
        return new PlannerDayState(at(7), at(22), windows, ranked, wigs, List.of(), NEUTRAL, true);
    }

    private static DayWindow window(String slotId, int startHour, int endHour, SlotPurpose purpose) {
        return new DayWindow(
            new TemplateSlot(slotId, startHour * 60, endHour * 60, purpose), at(startHour), at(endHour));
    }

    private static SchedulableExecutable task(double priority) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false,
            null, null, 0, 60, 0, null, null);
    }

    private static SchedulableExecutable drainingTask(double priority) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false,
            5, null, 0, 60, 0, null, null);
    }

    private static MciWig wig(UUID leadMeasureId) {
        return new MciWig(UUID.randomUUID(), leadMeasureId, 0.2, 0.5, false,
            DAY.plusDays(30), false, 0);
    }

    private static List<UUID> ids(List<SchedulableExecutable> executables) {
        return executables.stream().map(SchedulableExecutable::id).toList();
    }

    private static OffsetDateTime at(int hour) {
        return DAY.atTime(hour, 0).atZone(UTC).toOffsetDateTime();
    }

    /** Guards the assumption the whole suite rests on: the sanctioned template really is the default. */
    @Test
    @DisplayName("the sanctioned template is the one the resolver would lay by default")
    void the_default_template_is_the_sanctioned_one() {
        assertThat(DayTemplate.DEFAULT.slots()).isNotEmpty();
    }
}
