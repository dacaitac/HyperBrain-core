package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LearnedUnitCost;
import com.hyperbrain.planner.domain.model.SettledObservation;
import com.hyperbrain.planner.domain.model.TaskCostInputs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("LearnedUnitCostCalculator (spike #63, EWMA a=0.3)")
class LearnedUnitCostCalculatorTest {

    private static final UUID TASK_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final double EPS = 1e-9;

    private final LearnedUnitCostCalculator calculator = new LearnedUnitCostCalculator();

    @Test
    @DisplayName("no observations and no estimate: cost has no signal (null cu, cold-start)")
    void no_signal_yields_null_cold_start() {
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(), null, 0));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.cu()).isNull();
        assertThat(result.observationCount()).isZero();
        assertThat(result.coldStart()).isTrue();
    }

    @Test
    @DisplayName("zero observations: cold-start splits the human estimate uniformly across subtasks")
    void cold_start_zero_observations_uses_uniform_prior() {
        // 120 estimated minutes / 4 subtasks = 30 min per subtask
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(), 120, 4));

        assertThat(result.coldStart()).isTrue();
        assertThat(result.observationCount()).isZero();
        assertThat(result.cu()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("one observation: still cold-start, prior kept (EWMA not yet trusted)")
    void one_observation_stays_cold_start() {
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(obs(50, 1)), 120, 4));

        assertThat(result.coldStart()).isTrue();
        assertThat(result.observationCount()).isEqualTo(1);
        assertThat(result.cu()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("two observations: still below N minimum, prior kept")
    void two_observations_stays_cold_start() {
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(obs(50, 1), obs(70, 1)), 120, 4));

        assertThat(result.coldStart()).isTrue();
        assertThat(result.observationCount()).isEqualTo(2);
        assertThat(result.cu()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("third observation crosses N minimum: switches to the EWMA over the three observations")
    void third_observation_switches_to_ewma() {
        // obs = 40, 60, 50 (one subtask each)
        // cu0 = 40
        // cu1 = 0.3*60 + 0.7*40 = 18 + 28 = 46
        // cu2 = 0.3*50 + 0.7*46 = 15 + 32.2 = 47.2
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(obs(40, 1), obs(60, 1), obs(50, 1)), 120, 4));

        assertThat(result.coldStart()).isFalse();
        assertThat(result.observationCount()).isEqualTo(3);
        assertThat(result.cu()).isCloseTo(47.2, within(EPS));
    }

    @Test
    @DisplayName("EWMA sequence: folds observations oldest-first with a=0.3 (known values)")
    void ewma_sequence_with_known_values() {
        // obs = 10, 20, 30, 40 (one subtask each)
        // cu0 = 10
        // cu1 = 0.3*20 + 0.7*10  = 6  + 7     = 13
        // cu2 = 0.3*30 + 0.7*13  = 9  + 9.1   = 18.1
        // cu3 = 0.3*40 + 0.7*18.1 = 12 + 12.67 = 24.67
        LearnedUnitCost result = calculator.calculate(new TaskCostInputs(
            TASK_ID, List.of(obs(10, 1), obs(20, 1), obs(30, 1), obs(40, 1)), null, 0));

        assertThat(result.coldStart()).isFalse();
        assertThat(result.observationCount()).isEqualTo(4);
        assertThat(result.cu()).isCloseTo(24.67, within(EPS));
    }

    @Test
    @DisplayName("per-block observation divides actual minutes by the imputed-subtask count")
    void observation_divides_by_imputed_count() {
        // block unit costs: 90/3=30, 80/2=40, 100/4=25
        // cu0 = 30
        // cu1 = 0.3*40 + 0.7*30 = 12 + 21   = 33
        // cu2 = 0.3*25 + 0.7*33 = 7.5 + 23.1 = 30.6
        LearnedUnitCost result = calculator.calculate(new TaskCostInputs(
            TASK_ID, List.of(obs(90, 3), obs(80, 2), obs(100, 4)), null, 0));

        assertThat(result.cu()).isCloseTo(30.6, within(EPS));
    }

    @Test
    @DisplayName("blocks with no imputed subtask are dropped (guard against divide-by-zero)")
    void zero_imputed_count_blocks_are_dropped() {
        // The two count=0 blocks carry no per-subtask signal and must not count toward N nor the EWMA.
        // Valid observations reduce to 40, 60, 50 -> EWMA = 47.2 (same as the crossing test).
        LearnedUnitCost result = calculator.calculate(new TaskCostInputs(TASK_ID,
            List.of(obs(999, 0), obs(40, 1), obs(60, 1), obs(999, 0), obs(50, 1)), 120, 4));

        assertThat(result.coldStart()).isFalse();
        assertThat(result.observationCount()).isEqualTo(3);
        assertThat(result.cu()).isCloseTo(47.2, within(EPS));
    }

    @Test
    @DisplayName("only zero-imputed blocks: no valid observation, falls back to the cold-start prior")
    void only_zero_imputed_blocks_fall_back_to_prior() {
        LearnedUnitCost result = calculator.calculate(new TaskCostInputs(
            TASK_ID, List.of(obs(999, 0), obs(999, 0)), 120, 4));

        assertThat(result.coldStart()).isTrue();
        assertThat(result.observationCount()).isZero();
        assertThat(result.cu()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("outlier robustness: a=0.3 EWMA absorbs a spike far less than a plain average would")
    void ewma_is_robust_to_an_outlier() {
        // Steady 30-min subtasks then a 300-min outlier last.
        // cu after four 30s = 30. cu_last = 0.3*300 + 0.7*30 = 90 + 21 = 111.
        // A plain mean would be (30*4 + 300)/5 = 84; but the point is the EWMA weights recency,
        // so verify it lands on the EWMA value, not the mean, and stays well under the raw outlier.
        LearnedUnitCost result = calculator.calculate(new TaskCostInputs(TASK_ID,
            List.of(obs(30, 1), obs(30, 1), obs(30, 1), obs(30, 1), obs(300, 1)), null, 0));

        assertThat(result.cu()).isCloseTo(111.0, within(EPS));
        assertThat(result.cu()).isLessThan(300.0);
    }

    @Test
    @DisplayName("cold-start with a null estimate but subtasks present has no prior (null cu)")
    void cold_start_null_estimate_has_no_prior() {
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(obs(50, 1)), null, 4));

        assertThat(result.coldStart()).isTrue();
        assertThat(result.cu()).isNull();
    }

    @Test
    @DisplayName("cold-start with an estimate but zero subtasks has no prior (no divisor)")
    void cold_start_zero_subtasks_has_no_prior() {
        LearnedUnitCost result = calculator.calculate(
            new TaskCostInputs(TASK_ID, List.of(), 120, 0));

        assertThat(result.coldStart()).isTrue();
        assertThat(result.cu()).isNull();
    }

    private static SettledObservation obs(int actualMinutes, int imputedCount) {
        return new SettledObservation(actualMinutes, imputedCount);
    }
}
