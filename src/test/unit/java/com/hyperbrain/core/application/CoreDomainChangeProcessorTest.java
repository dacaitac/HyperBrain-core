package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.CompletionOutcomeRule;
import com.hyperbrain.core.application.rule.CompletionReactivationRule;
import com.hyperbrain.core.application.rule.ContainmentCopyRule;
import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.core.application.rule.RecurrenceCloneRule;
import com.hyperbrain.core.application.rule.ProgressRecalculationRule;
import com.hyperbrain.core.application.rule.ReestimationConfirmationRule;
import com.hyperbrain.core.application.rule.SingleFocusRule;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CoreDomainChangeProcessor")
class CoreDomainChangeProcessorTest {

    private EndTimeInvariantRule endTimeRule;
    private CompletionReactivationRule completionReactivationRule;
    private ContainmentCopyRule containmentCopyRule;
    private SingleFocusRule focusRule;
    private ReestimationConfirmationRule reestimationRule;
    private ProgressRecalculationRule progressRule;
    private CompletionOutcomeRule completionOutcomeRule;
    private RecurrenceCloneRule habitRule;
    private CoreDomainChangeProcessor processor;

    @BeforeEach
    void setUp() {
        endTimeRule = mock(EndTimeInvariantRule.class);
        completionReactivationRule = mock(CompletionReactivationRule.class);
        containmentCopyRule = mock(ContainmentCopyRule.class);
        focusRule = mock(SingleFocusRule.class);
        reestimationRule = mock(ReestimationConfirmationRule.class);
        progressRule = mock(ProgressRecalculationRule.class);
        completionOutcomeRule = mock(CompletionOutcomeRule.class);
        habitRule = mock(RecurrenceCloneRule.class);
        processor = new CoreDomainChangeProcessor(
            endTimeRule, completionReactivationRule, containmentCopyRule, focusRule,
            reestimationRule, progressRule, completionOutcomeRule, habitRule);
    }

    @Test
    @DisplayName("applies the DR chain in order (DR-01→DR-02→copy→DR-05/06→DR-07→outcome→DR-04), threading each rule's output into the next")
    void applies_chain_in_order() {
        ExecutableSnapshot previous = ExecutableSnapshotBuilder.snapshot().status("TODO").build();
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().status("IN_PROGRESS").build();
        ExecutableSnapshot afterEndTime = ExecutableSnapshotBuilder.snapshot().name("a").build();
        ExecutableSnapshot afterReactivation = ExecutableSnapshotBuilder.snapshot().name("b").build();
        ExecutableSnapshot afterCopy = ExecutableSnapshotBuilder.snapshot().name("bc").build();
        ExecutableSnapshot afterFocus = ExecutableSnapshotBuilder.snapshot().name("c").build();
        ExecutableSnapshot afterReestimation = ExecutableSnapshotBuilder.snapshot().name("d").build();
        ExecutableSnapshot afterProgress = ExecutableSnapshotBuilder.snapshot().name("e").build();
        ExecutableSnapshot afterOutcome = ExecutableSnapshotBuilder.snapshot().name("eo").build();
        ExecutableSnapshot afterHabit = ExecutableSnapshotBuilder.snapshot().name("f").build();
        when(endTimeRule.apply(same(previous), same(merged), eq(ExternalSystem.NOTION)))
            .thenReturn(afterEndTime);
        when(completionReactivationRule.apply(same(previous), same(afterEndTime), eq(ExternalSystem.NOTION)))
            .thenReturn(afterReactivation);
        when(containmentCopyRule.apply(same(previous), same(afterReactivation), eq(ExternalSystem.NOTION)))
            .thenReturn(afterCopy);
        when(focusRule.apply(same(previous), same(afterCopy), eq(ExternalSystem.NOTION)))
            .thenReturn(afterFocus);
        when(reestimationRule.apply(same(previous), same(afterFocus), eq(ExternalSystem.NOTION)))
            .thenReturn(afterReestimation);
        when(progressRule.apply(same(previous), same(afterReestimation), eq(ExternalSystem.NOTION)))
            .thenReturn(afterProgress);
        when(completionOutcomeRule.apply(same(previous), same(afterProgress), eq(ExternalSystem.NOTION)))
            .thenReturn(afterOutcome);
        when(habitRule.apply(same(previous), same(afterOutcome), eq(ExternalSystem.NOTION)))
            .thenReturn(afterHabit);

        ExecutableSnapshot result = processor.process(previous, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(afterHabit);
        InOrder order = inOrder(endTimeRule, completionReactivationRule, containmentCopyRule,
            focusRule, reestimationRule, progressRule, completionOutcomeRule, habitRule);
        order.verify(endTimeRule).apply(any(), any(), any());
        order.verify(completionReactivationRule).apply(any(), any(), any());
        order.verify(containmentCopyRule).apply(any(), any(), any());
        order.verify(focusRule).apply(any(), any(), any());
        order.verify(reestimationRule).apply(any(), any(), any());
        order.verify(progressRule).apply(any(), any(), any());
        order.verify(completionOutcomeRule).apply(any(), any(), any());
        order.verify(habitRule).apply(any(), any(), any());
    }
}
