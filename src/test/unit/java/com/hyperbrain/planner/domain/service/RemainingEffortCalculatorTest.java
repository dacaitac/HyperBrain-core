package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RemainingEffortCalculator (#6a, block sizing by remaining effort)")
class RemainingEffortCalculatorTest {

    private static final UUID ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Test
    @DisplayName("with subtasks: remaining = pendingSubtasks * cu")
    void with_subtasks_multiplies_cu() {
        SchedulableExecutable executable = withSubtasks(3, 20.0);

        assertThat(RemainingEffortCalculator.remainingMinutes(executable)).isEqualTo(60);
    }

    @Test
    @DisplayName("with subtasks: cu is rounded to whole minutes")
    void with_subtasks_rounds() {
        SchedulableExecutable executable = withSubtasks(3, 20.5); // 61.5 -> 62

        assertThat(RemainingEffortCalculator.remainingMinutes(executable)).isEqualTo(62);
    }

    @Test
    @DisplayName("without subtasks: remaining = max(estimated - settled, 0)")
    void without_subtasks_subtracts_settled() {
        SchedulableExecutable executable = withoutSubtasks(90, 30);

        assertThat(RemainingEffortCalculator.remainingMinutes(executable)).isEqualTo(60);
    }

    @Test
    @DisplayName("without subtasks: overrun clamps the remaining to zero, never negative")
    void without_subtasks_clamps_overrun() {
        SchedulableExecutable executable = withoutSubtasks(60, 100);

        assertThat(RemainingEffortCalculator.remainingMinutes(executable)).isZero();
    }

    @Test
    @DisplayName("no cu and no estimate: no signal, zero effort")
    void no_signal_is_zero() {
        SchedulableExecutable executable = new SchedulableExecutable(
            ID, ExecutableType.TASK, 0.5, false, null, null, 0, null, 0);

        assertThat(RemainingEffortCalculator.remainingMinutes(executable)).isZero();
    }

    private static SchedulableExecutable withSubtasks(int pending, double cu) {
        return new SchedulableExecutable(ID, ExecutableType.TASK, 0.5, false, null, cu, pending, null, 0);
    }

    private static SchedulableExecutable withoutSubtasks(int estimated, int settled) {
        return new SchedulableExecutable(
            ID, ExecutableType.TASK, 0.5, false, null, null, 0, estimated, settled);
    }
}
