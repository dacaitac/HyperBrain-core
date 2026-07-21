package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyTier;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HumanizedAgendaFloor (H1, composed humanized deterministic floor)")
class HumanizedAgendaFloorTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);

    /** No chaos margin, so the ranking may use the whole window — humanization does the trimming. */
    private static final EnergyProfile FULL_WINDOW =
        new EnergyProfile(EnergyTier.NEUTRAL, 0.0, 16, "no chaos margin");

    @Test
    @DisplayName("rule 1: a transition buffer spaces consecutive blocks apart")
    void inserts_transition_buffer_between_blocks() {
        HumanizationSettings settings = settings(5, List.of(), 0, 0.0, 1.0);
        SchedulableExecutable a = task(0.9, 60);
        SchedulableExecutable b = task(0.8, 60);

        Agenda agenda = floor(settings).generate(
            state(WAKE, BEDTIME, List.of(a, b), List.of(), List.of()));

        assertThat(agenda.blocks()).hasSize(2);
        AgendaBlock first = agenda.blocks().get(0);
        AgendaBlock second = agenda.blocks().get(1);
        assertThat(Duration.between(first.end(), second.start()).toMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("rule 2: no block invades a protected meal-anchor wall")
    void never_invades_meal_window() {
        HumanizationSettings settings = settings(0, List.of(), 0, 0.0, 1.0);
        OccupiedInterval lunch = new OccupiedInterval(null,
            OffsetDateTime.of(2026, 7, 10, 12, 30, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 7, 10, 13, 30, 0, 0, ZoneOffset.UTC), false);
        List<SchedulableExecutable> ranked = IntStream.range(0, 8)
            .mapToObj(i -> task(0.9 - i * 0.01, 60)).toList();

        Agenda agenda = floor(settings).generate(
            state(WAKE, BEDTIME, ranked, List.of(), List.of(lunch)));

        assertThat(agenda.blocks()).allSatisfy(block ->
            assertThat(block.start().isBefore(lunch.end()) && block.end().isAfter(lunch.start()))
                .as("block %s-%s must not overlap lunch", block.start(), block.end())
                .isFalse());
    }

    @Test
    @DisplayName("rule 3: a sub-minimum block is not left as a sliver")
    void drops_sub_minimum_slivers() {
        HumanizationSettings settings = settings(0, List.of(), 15, 0.0, 1.0);
        SchedulableExecutable sliver = task(0.9, 10);
        SchedulableExecutable viable = task(0.8, 60);

        Agenda agenda = floor(settings).generate(
            state(WAKE, BEDTIME, List.of(sliver, viable), List.of(), List.of()));

        assertThat(agenda.blocks()).extracting(AgendaBlock::executableId).containsExactly(viable.id());
        assertThat(agenda.blocks()).allSatisfy(block ->
            assertThat(block.durationMinutes()).isGreaterThanOrEqualTo(15));
        assertThat(agenda.excluded()).anyMatch(e ->
            e.executableId().equals(sliver.id()) && e.reason() == ExclusionReason.BELOW_MIN_BLOCK);
    }

    @Test
    @DisplayName("rule 4: same-context work is batched adjacently without breaking priority")
    void batches_same_context_adjacently() {
        HumanizationSettings settings = settings(0, List.of(), 0, 0.10, 1.0);
        UUID cycleA = UUID.randomUUID();
        UUID cycleB = UUID.randomUUID();
        SchedulableExecutable a = task(cycleA, 0.90, 30);
        SchedulableExecutable b = task(cycleB, 0.88, 30);
        SchedulableExecutable c = task(cycleA, 0.86, 30);

        Agenda agenda = floor(settings).generate(
            state(WAKE, BEDTIME, List.of(a, b, c), List.of(), List.of()));

        // Same-cycle A and C are placed adjacently (A, C), then B — all within one comparable band.
        assertThat(agenda.blocks()).extracting(AgendaBlock::executableId)
            .containsExactly(a.id(), c.id(), b.id());
    }

    @Test
    @DisplayName("rule 6: the day is filled inside the 75–85% occupancy band, never packed to 100%")
    void respects_occupancy_band() {
        HumanizationSettings settings = settings(0, List.of(), 0, 0.0, 0.85);
        // Sixteen 60-min tasks would fill the 960-min window to 100%; the cap trims it back.
        List<SchedulableExecutable> ranked = IntStream.range(0, 16)
            .mapToObj(i -> task(0.99 - i * 0.01, 60)).toList();

        Agenda agenda = floor(settings).generate(
            state(WAKE, BEDTIME, ranked, List.of(), List.of()));

        long busy = agenda.blocks().stream().mapToLong(AgendaBlock::durationMinutes).sum();
        double occupancy = busy / 960.0;
        assertThat(occupancy).isBetween(0.75, 0.85);
        assertThat(agenda.excluded()).anyMatch(e -> e.reason() == ExclusionReason.OVER_OCCUPANCY_CAP);
    }

    @Test
    @DisplayName("rule 5: an anchored habit lands at the same local time across days, ignoring run clutter")
    void habit_anchor_is_stable_across_days() {
        HumanizationSettings settings = settings(0, List.of(), 0, 0.0, 1.0);
        UUID habitId = UUID.randomUUID();

        // Day 1: habit anchored at 08:00 with light clutter around it.
        OffsetDateTime day1Anchor = OffsetDateTime.of(2026, 7, 10, 8, 0, 0, 0, ZoneOffset.UTC);
        SchedulableExecutable habitDay1 = habit(habitId, day1Anchor, 45);
        Agenda day1 = floor(settings).generate(state(WAKE, BEDTIME,
            List.of(task(0.40, 30), habitDay1), List.of(), List.of()));

        // Day 2: same habit identity, same anchor, a different amount and shape of clutter. The anchor
        // is a property of the habit's identity, not of where the run happens to reach it.
        OffsetDateTime wake2 = WAKE.plusDays(1);
        OffsetDateTime bedtime2 = BEDTIME.plusDays(1);
        OffsetDateTime day2Anchor = OffsetDateTime.of(2026, 7, 11, 8, 0, 0, 0, ZoneOffset.UTC);
        SchedulableExecutable habitDay2 = habit(habitId, day2Anchor, 45);
        Agenda day2 = floor(settings).generate(state(wake2, bedtime2,
            List.of(task(0.30, 30), task(0.20, 30), task(0.10, 30), habitDay2), List.of(), List.of()));

        assertThat(habitBlockLocalHour(day1, habitId)).isEqualTo(8);
        assertThat(habitBlockLocalHour(day2, habitId)).isEqualTo(8);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static HumanizedAgendaFloor floor(HumanizationSettings settings) {
        return new HumanizedAgendaFloor(
            new ContextBatcher(),
            new AgendaGenerator(PlannerConstraints.DEFAULT, settings),
            new AgendaHumanizer(),
            settings);
    }

    private static int habitBlockLocalHour(Agenda agenda, UUID habitId) {
        return agenda.blocks().stream()
            .filter(b -> b.executableId().equals(habitId))
            .findFirst()
            .orElseThrow()
            .start()
            .getHour();
    }

    private static PlannerDayState state(OffsetDateTime windowStart, OffsetDateTime windowEnd,
                                         List<SchedulableExecutable> ranked, List<MciWig> wigs,
                                         List<OccupiedInterval> occupied) {
        return new PlannerDayState(
            windowStart, windowEnd, ranked, wigs, new ArrayList<>(occupied), FULL_WINDOW, true);
    }

    private static HumanizationSettings settings(int buffer, List<com.hyperbrain.planner.domain.model.MealWindow> meals,
                                                 int minBlock, double band, double occMax) {
        double occMin = Math.min(0.0, occMax);
        return new HumanizationSettings(buffer, meals, minBlock, band, occMin, occMax);
    }

    private static SchedulableExecutable task(double priority, int minutes) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, null,
            null, 0, minutes, 0, null, null);
    }

    private static SchedulableExecutable task(UUID cycleId, double priority, int minutes) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, null,
            null, 0, minutes, 0, null, cycleId);
    }

    private static SchedulableExecutable habit(UUID id, OffsetDateTime anchor, int minutes) {
        return new SchedulableExecutable(id, ExecutableType.HABIT, 0.5, false, null,
            null, 0, minutes, 0, anchor, null);
    }
}
