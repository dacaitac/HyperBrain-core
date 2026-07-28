package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AffinityGrouper (core#50 — deterministic affinity grouping)")
class AffinityGrouperTest {

    private static final int DRAIN_FLOOR = 4;
    private static final double BAND = 0.10;
    private static final int MAX_MEMBERS = 4;

    private final AffinityGrouper grouper = new AffinityGrouper();

    private static final UUID CYCLE_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CYCLE_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Test
    @DisplayName("adjacent same-context executables within the band merge into one group")
    void groups_same_context_within_band() {
        SchedulableExecutable a = task(0.90, CYCLE_A);
        SchedulableExecutable b = task(0.85, CYCLE_A);

        List<List<SchedulableExecutable>> groups = grouper.group(List.of(a, b), DRAIN_FLOOR, BAND, MAX_MEMBERS);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(a, b);
    }

    @Test
    @DisplayName("a different context opens a new group even at a comparable priority")
    void different_context_opens_new_group() {
        SchedulableExecutable a = task(0.90, CYCLE_A);
        SchedulableExecutable b = task(0.88, CYCLE_B);

        List<List<SchedulableExecutable>> groups = grouper.group(List.of(a, b), DRAIN_FLOOR, BAND, MAX_MEMBERS);

        assertThat(groups).extracting(List::size).containsExactly(1, 1);
    }

    @Test
    @DisplayName("a priority drop beyond the band closes the group, even in the same context")
    void priority_drop_beyond_band_closes_group() {
        SchedulableExecutable leader = task(0.90, CYCLE_A);
        SchedulableExecutable farther = task(0.70, CYCLE_A); // 0.20 below the leader > band 0.10

        List<List<SchedulableExecutable>> groups =
            grouper.group(List.of(leader, farther), DRAIN_FLOOR, BAND, MAX_MEMBERS);

        assertThat(groups).hasSize(2);
    }

    @Test
    @DisplayName("the WIP cap closes a group at maxMembers; the overflow opens a new group")
    void wip_cap_bounds_group_size() {
        SchedulableExecutable a = task(0.90, CYCLE_A);
        SchedulableExecutable b = task(0.89, CYCLE_A);
        SchedulableExecutable c = task(0.88, CYCLE_A);

        List<List<SchedulableExecutable>> groups =
            grouper.group(List.of(a, b, c), DRAIN_FLOOR, BAND, 2);

        assertThat(groups).extracting(List::size).containsExactly(2, 1);
    }

    @Test
    @DisplayName("load compatibility: a group never stacks two high-load members")
    void never_stacks_two_high_load_members() {
        SchedulableExecutable highA = highLoad(0.90, CYCLE_A);
        SchedulableExecutable highB = highLoad(0.89, CYCLE_A);

        List<List<SchedulableExecutable>> groups =
            grouper.group(List.of(highA, highB), DRAIN_FLOOR, BAND, MAX_MEMBERS);

        assertThat(groups).extracting(List::size).containsExactly(1, 1);
    }

    @Test
    @DisplayName("one high-load member may still ride with a standard member")
    void one_high_load_with_standard_is_allowed() {
        SchedulableExecutable high = highLoad(0.90, CYCLE_A);
        SchedulableExecutable standard = task(0.89, CYCLE_A);

        List<List<SchedulableExecutable>> groups =
            grouper.group(List.of(high, standard), DRAIN_FLOOR, BAND, MAX_MEMBERS);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(high, standard);
    }

    @Test
    @DisplayName("a zero (or negative) band disables grouping: every executable is its own singleton")
    void zero_band_disables_grouping() {
        SchedulableExecutable a = task(0.90, CYCLE_A);
        SchedulableExecutable b = task(0.90, CYCLE_A);

        List<List<SchedulableExecutable>> groups = grouper.group(List.of(a, b), DRAIN_FLOOR, 0.0, MAX_MEMBERS);

        assertThat(groups).extracting(List::size).containsExactly(1, 1);
    }

    @Test
    @DisplayName("an empty input yields no groups")
    void empty_input_yields_no_groups() {
        assertThat(grouper.group(List.of(), DRAIN_FLOOR, BAND, MAX_MEMBERS)).isEmpty();
    }

    private static SchedulableExecutable task(double priority, UUID cycleId) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, null,
            null, 0, 60, 0, null, cycleId);
    }

    private static SchedulableExecutable highLoad(double priority, UUID cycleId) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, 5,
            null, 0, 60, 0, null, cycleId);
    }
}
