package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyTier;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
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

@DisplayName("AgendaInputHasher — stable digest of the generator input (HU-01c H2)")
class AgendaInputHasherTest {

    private static final OffsetDateTime WINDOW_START =
        OffsetDateTime.of(2026, 7, 10, 6, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime WINDOW_END =
        OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID TASK = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AgendaInputHasher hasher = new AgendaInputHasher();

    @Test
    @DisplayName("the same input state hashes identically (deterministic)")
    void deterministic_for_equal_state() {
        PlannerDayState first = state(WINDOW_START, List.of(task(TASK, 0.9)));
        PlannerDayState second = state(WINDOW_START, List.of(task(TASK, 0.9)));

        assertThat(h(first)).isEqualTo(h(second));
    }

    @Test
    @DisplayName("sub-minute jitter of the frontier is ignored (window bounds truncated to the minute)")
    void ignores_sub_minute_jitter() {
        PlannerDayState onTheMinute = state(WINDOW_START, List.of(task(TASK, 0.9)));
        PlannerDayState secondsLater =
            state(WINDOW_START.plusSeconds(47), List.of(task(TASK, 0.9)));

        assertThat(h(secondsLater)).isEqualTo(h(onTheMinute));
    }

    @Test
    @DisplayName("a later temporal frontier (a new minute) hashes differently — a real replan is not deduped")
    void distinct_when_frontier_advances_a_minute() {
        PlannerDayState atStart = state(WINDOW_START, List.of(task(TASK, 0.9)));
        PlannerDayState aMinuteLater =
            state(WINDOW_START.plusMinutes(1), List.of(task(TASK, 0.9)));

        assertThat(h(aMinuteLater)).isNotEqualTo(h(atStart));
    }

    @Test
    @DisplayName("a changed task rank changes the hash")
    void distinct_when_rank_changes() {
        PlannerDayState low = state(WINDOW_START, List.of(task(TASK, 0.2)));
        PlannerDayState high = state(WINDOW_START, List.of(task(TASK, 0.9)));

        assertThat(h(high)).isNotEqualTo(h(low));
    }

    @Test
    @DisplayName("a changed energy tier changes the hash")
    void distinct_when_energy_changes() {
        PlannerDayState neutral = state(WINDOW_START, List.of(task(TASK, 0.9)),
            new EnergyProfile(EnergyTier.NEUTRAL, 0.15, 3, "neutral"), List.of());
        PlannerDayState low = state(WINDOW_START, List.of(task(TASK, 0.9)),
            new EnergyProfile(EnergyTier.LOW, 0.15, 3, "low"), List.of());

        assertThat(h(low)).isNotEqualTo(h(neutral));
    }

    @Test
    @DisplayName("a changed wall changes the hash, but wall ordering does not (walls sorted)")
    void walls_change_matters_order_does_not() {
        OccupiedInterval a = new OccupiedInterval(null,
            OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 7, 10, 10, 0, 0, 0, ZoneOffset.UTC), false);
        OccupiedInterval b = new OccupiedInterval(null,
            OffsetDateTime.of(2026, 7, 10, 14, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 7, 10, 15, 0, 0, 0, ZoneOffset.UTC), false);

        PlannerDayState ab = state(WINDOW_START, List.of(task(TASK, 0.9)), neutralEnergy(), List.of(a, b));
        PlannerDayState ba = state(WINDOW_START, List.of(task(TASK, 0.9)), neutralEnergy(), List.of(b, a));
        PlannerDayState onlyA = state(WINDOW_START, List.of(task(TASK, 0.9)), neutralEnergy(), List.of(a));

        assertThat(h(ba)).isEqualTo(h(ab));
        assertThat(h(onlyA)).isNotEqualTo(h(ab));
    }

    @Test
    @DisplayName("nullable executable fields are handled without collision")
    void nullable_fields_do_not_collide() {
        SchedulableExecutable sparse = new SchedulableExecutable(
            TASK, ExecutableType.TASK, null, false, null, null, 0, null, 0, null, null);
        SchedulableExecutable rich = new SchedulableExecutable(
            TASK, ExecutableType.TASK, 0.5, false, 4, null, 0, 60, 0, null, null);

        PlannerDayState sparseState = state(WINDOW_START, List.of(sparse));
        PlannerDayState richState = state(WINDOW_START, List.of(rich));

        assertThat(h(sparseState)).isNotEqualTo(h(richState));
        // A pure-null executable still yields a stable, non-blank digest.
        assertThat(h(sparseState)).isNotBlank();
    }

    @Test
    @DisplayName("the WIG portfolio is part of the digest")
    void distinct_when_wig_changes() {
        MciWig wig = new MciWig(UUID.fromString("22222222-2222-2222-2222-222222222222"),
            null, 0.4, 0.5, false, LocalDate.of(2026, 8, 1), false, 0);
        PlannerDayState withWig = new PlannerDayState(WINDOW_START, WINDOW_END,
            List.of(task(TASK, 0.9)), List.of(wig), List.of(), neutralEnergy(), true);
        PlannerDayState withoutWig = state(WINDOW_START, List.of(task(TASK, 0.9)));

        assertThat(h(withWig)).isNotEqualTo(h(withoutWig));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Hashes with the state's own occupancy as the wall set (the production caller filters walls). */
    private String h(PlannerDayState state) {
        return hasher.hash(state, state.occupied());
    }

    private static EnergyProfile neutralEnergy() {
        return new EnergyProfile(EnergyTier.NEUTRAL, 0.15, 3, "neutral");
    }

    private static SchedulableExecutable task(UUID id, double priority) {
        return new SchedulableExecutable(
            id, ExecutableType.TASK, priority, false, 3, null, 0, 60, 0, null, null);
    }

    private static PlannerDayState state(OffsetDateTime windowStart,
                                         List<SchedulableExecutable> ranked) {
        return state(windowStart, ranked, neutralEnergy(), List.of());
    }

    private static PlannerDayState state(OffsetDateTime windowStart,
                                         List<SchedulableExecutable> ranked,
                                         EnergyProfile energy,
                                         List<OccupiedInterval> occupied) {
        return new PlannerDayState(windowStart, WINDOW_END, ranked, List.of(), occupied, energy, true);
    }
}
