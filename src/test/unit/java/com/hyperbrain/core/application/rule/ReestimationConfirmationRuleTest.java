package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ReestimationConfirmationRule (DR-06 confirmation)")
class ReestimationConfirmationRuleTest {

    private ExecutableStateRepository stateRepo;
    private ReestimationConfirmationRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        rule = new ReestimationConfirmationRule(stateRepo);
    }

    @Test
    @DisplayName("non-null effort from a human source clears the pending flag")
    void human_effort_clears_pending() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().effortScore(2.5).build();
        when(stateRepo.clearPendingReestimation(merged.id())).thenReturn(true);

        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(merged);
        verify(stateRepo).clearPendingReestimation(merged.id());
    }

    @Test
    @DisplayName("profile-only effort values (impact) also confirm")
    void profile_effort_confirms() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().impact(5).build();
        when(stateRepo.clearPendingReestimation(merged.id())).thenReturn(true);

        rule.apply(null, merged, ExternalSystem.APPLE);

        verify(stateRepo).clearPendingReestimation(merged.id());
    }

    @Test
    @DisplayName("SYSTEM origin never confirms — the confirmation is human")
    void system_origin_does_not_confirm() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().effortScore(2.5).build();

        rule.apply(null, merged, ExternalSystem.SYSTEM);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("a snapshot without effort values does not confirm")
    void no_effort_does_not_confirm() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().build();

        rule.apply(null, merged, ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }
}
