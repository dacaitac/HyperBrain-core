package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextBatcher (H1 rule 4, context batching within priority bands)")
class ContextBatcherTest {

    private static final UUID CYCLE_A = UUID.randomUUID();
    private static final UUID CYCLE_B = UUID.randomUUID();

    private final ContextBatcher batcher = new ContextBatcher();

    @Test
    @DisplayName("within one band, same-cycle executables are pulled adjacent without dropping order")
    void groups_same_context_within_a_band() {
        // A(cycleA,0.90), B(cycleB,0.88), C(cycleA,0.86) — all within 0.10 of the leader, one band.
        SchedulableExecutable a = exec(CYCLE_A, 0.90);
        SchedulableExecutable b = exec(CYCLE_B, 0.88);
        SchedulableExecutable c = exec(CYCLE_A, 0.86);

        List<SchedulableExecutable> batched = batcher.batch(List.of(a, b, c), 0.10);

        // cycleA (first seen at A) groups A then C; cycleB (B) follows.
        assertThat(batched).containsExactly(a, c, b);
    }

    @Test
    @DisplayName("batching never crosses a band: a lower-priority same-cycle item is not pulled up")
    void does_not_cross_bands() {
        // A(cycleA,0.90) is its own band; B(cycleB,0.50) and C(cycleA,0.48) form the next band.
        SchedulableExecutable a = exec(CYCLE_A, 0.90);
        SchedulableExecutable b = exec(CYCLE_B, 0.50);
        SchedulableExecutable c = exec(CYCLE_A, 0.48);

        List<SchedulableExecutable> batched = batcher.batch(List.of(a, b, c), 0.10);

        // C stays behind B — it belongs to the lower band and is never promoted next to A.
        assertThat(batched).containsExactly(a, b, c);
    }

    @Test
    @DisplayName("a zero band width disables batching and preserves the ranked order")
    void zero_band_disables_batching() {
        SchedulableExecutable a = exec(CYCLE_A, 0.90);
        SchedulableExecutable b = exec(CYCLE_B, 0.88);
        SchedulableExecutable c = exec(CYCLE_A, 0.86);

        List<SchedulableExecutable> batched = batcher.batch(List.of(a, b, c), 0.0);

        assertThat(batched).containsExactly(a, b, c);
    }

    @Test
    @DisplayName("executables without a cycle batch by type as the fallback context key")
    void batches_by_type_when_no_cycle() {
        SchedulableExecutable task1 = typed(ExecutableType.TASK, 0.90);
        SchedulableExecutable habit = typed(ExecutableType.HABIT, 0.88);
        SchedulableExecutable task2 = typed(ExecutableType.TASK, 0.86);

        List<SchedulableExecutable> batched = batcher.batch(List.of(task1, habit, task2), 0.10);

        assertThat(batched).containsExactly(task1, task2, habit);
    }

    private static SchedulableExecutable exec(UUID cycleId, double priority) {
        return new SchedulableExecutable(UUID.randomUUID(), ExecutableType.TASK, priority, false, null,
            null, 0, 30, 0, null, cycleId);
    }

    private static SchedulableExecutable typed(ExecutableType type, double priority) {
        return new SchedulableExecutable(UUID.randomUUID(), type, priority, false, null,
            null, 0, 30, 0, null, null);
    }
}
