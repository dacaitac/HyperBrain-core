package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.CompletionOutcomeRule;
import com.hyperbrain.core.application.rule.CompletionReactivationRule;
import com.hyperbrain.core.application.rule.ContainmentCopyRule;
import com.hyperbrain.core.application.rule.ContainmentEligibilityRule;
import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.core.application.rule.RecurrenceCloneRule;
import com.hyperbrain.core.application.rule.ProgressRecalculationRule;
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
    private ContainmentEligibilityRule containmentEligibilityRule;
    private ContainmentCopyRule containmentCopyRule;
    private ProgressRecalculationRule progressRule;
    private CompletionOutcomeRule completionOutcomeRule;
    private RecurrenceCloneRule habitRule;
    private CoreDomainChangeProcessor processor;

    @BeforeEach
    void setUp() {
        endTimeRule = mock(EndTimeInvariantRule.class);
        completionReactivationRule = mock(CompletionReactivationRule.class);
        containmentEligibilityRule = mock(ContainmentEligibilityRule.class);
        containmentCopyRule = mock(ContainmentCopyRule.class);
        progressRule = mock(ProgressRecalculationRule.class);
        completionOutcomeRule = mock(CompletionOutcomeRule.class);
        habitRule = mock(RecurrenceCloneRule.class);
        processor = new CoreDomainChangeProcessor(
            endTimeRule, completionReactivationRule, containmentEligibilityRule, containmentCopyRule,
            progressRule, completionOutcomeRule, habitRule);
    }

    @Test
    @DisplayName("applies the rule chain in order, threading each rule's output into the next")
    void applies_chain_in_order() {
        ExecutableSnapshot previous = ExecutableSnapshotBuilder.snapshot().status("TODO").build();
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().status("IN_PROGRESS").build();
        ExecutableSnapshot afterEndTime = ExecutableSnapshotBuilder.snapshot().name("a").build();
        ExecutableSnapshot afterReactivation = ExecutableSnapshotBuilder.snapshot().name("b").build();
        ExecutableSnapshot afterEligibility = ExecutableSnapshotBuilder.snapshot().name("be").build();
        ExecutableSnapshot afterCopy = ExecutableSnapshotBuilder.snapshot().name("bc").build();
        ExecutableSnapshot afterProgress = ExecutableSnapshotBuilder.snapshot().name("e").build();
        ExecutableSnapshot afterOutcome = ExecutableSnapshotBuilder.snapshot().name("eo").build();
        ExecutableSnapshot afterHabit = ExecutableSnapshotBuilder.snapshot().name("f").build();
        when(endTimeRule.apply(same(previous), same(merged), eq(ExternalSystem.NOTION)))
            .thenReturn(afterEndTime);
        when(completionReactivationRule.apply(same(previous), same(afterEndTime), eq(ExternalSystem.NOTION)))
            .thenReturn(afterReactivation);
        when(containmentEligibilityRule.apply(same(previous), same(afterReactivation), eq(ExternalSystem.NOTION)))
            .thenReturn(afterEligibility);
        when(containmentCopyRule.apply(same(previous), same(afterEligibility), eq(ExternalSystem.NOTION)))
            .thenReturn(afterCopy);
        when(progressRule.apply(same(previous), same(afterCopy), eq(ExternalSystem.NOTION)))
            .thenReturn(afterProgress);
        when(completionOutcomeRule.apply(same(previous), same(afterProgress), eq(ExternalSystem.NOTION)))
            .thenReturn(afterOutcome);
        when(habitRule.apply(same(previous), same(afterOutcome), eq(ExternalSystem.NOTION)))
            .thenReturn(afterHabit);

        ExecutableSnapshot result = processor.process(previous, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(afterHabit);
        InOrder order = inOrder(endTimeRule, completionReactivationRule, containmentEligibilityRule,
            containmentCopyRule, progressRule, completionOutcomeRule, habitRule);
        order.verify(endTimeRule).apply(any(), any(), any());
        order.verify(completionReactivationRule).apply(any(), any(), any());
        order.verify(containmentEligibilityRule).apply(any(), any(), any());
        order.verify(containmentCopyRule).apply(any(), any(), any());
        order.verify(progressRule).apply(any(), any(), any());
        order.verify(completionOutcomeRule).apply(any(), any(), any());
        order.verify(habitRule).apply(any(), any(), any());
    }
}
