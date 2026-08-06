package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("CompletionOutcomeRule (ADR-039 FAILED matrix)")
class CompletionOutcomeRuleTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000f1");

    private ExecutableStateRepository stateRepo;
    private CompletionOutcomeRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        rule = new CompletionOutcomeRule(stateRepo);
    }

    @Test
    @DisplayName("DONE closure stamps the completion clock and extends the streak")
    void done_stamps_and_extends_streak() {
        rule.apply(row("IN_PROGRESS"), row("DONE"), ExternalSystem.NOTION);

        verify(stateRepo).markCompleted(eq(ID), any(OffsetDateTime.class));
        verify(stateRepo).recordAchievedStreak(ID);
        verify(stateRepo, never()).resetStreak(any());
    }

    @Test
    @DisplayName("FAILED closure stamps the completion clock but RESETS the streak (never extends)")
    void failed_stamps_and_resets_streak() {
        rule.apply(row("IN_PROGRESS"), row("FAILED"), ExternalSystem.NOTION);

        verify(stateRepo).markCompleted(eq(ID), any(OffsetDateTime.class));
        verify(stateRepo).resetStreak(ID);
        verify(stateRepo, never()).recordAchievedStreak(any());
    }

    @Test
    @DisplayName("re-ingesting an already-closed row is a no-op (transition-only)")
    void already_closed_is_noop() {
        rule.apply(row("DONE"), row("DONE"), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("FAILED → DONE (still closed both sides) does not re-fire")
    void failed_to_done_does_not_refire() {
        rule.apply(row("FAILED"), row("DONE"), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("a TIME_BLOCK closure is owned by the settlement, never this rule")
    void time_block_is_ignored() {
        rule.apply(row("IN_PROGRESS"),
            ExecutableSnapshotBuilder.snapshot().id(ID).type("TIME_BLOCK").status("FAILED").build(),
            ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("a system-generated snapshot never touches streaks")
    void system_generated_is_ignored() {
        rule.apply(row("IN_PROGRESS"),
            ExecutableSnapshotBuilder.snapshot().id(ID).status("DONE").systemGenerated(true).build(),
            ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("still-open transitions are ignored")
    void open_transition_is_noop() {
        ExecutableSnapshot result = rule.apply(row("TODO"), row("IN_PROGRESS"), ExternalSystem.NOTION);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(stateRepo);
    }

    private static ExecutableSnapshot row(String status) {
        return ExecutableSnapshotBuilder.snapshot().id(ID).type("HABIT").status(status).build();
    }
}
