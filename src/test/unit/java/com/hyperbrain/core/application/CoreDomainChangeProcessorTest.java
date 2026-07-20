package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.core.application.rule.HabitRecurrenceRule;
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
    private SingleFocusRule focusRule;
    private ReestimationConfirmationRule reestimationRule;
    private ProgressRecalculationRule progressRule;
    private HabitRecurrenceRule habitRule;
    private CoreDomainChangeProcessor processor;

    @BeforeEach
    void setUp() {
        endTimeRule = mock(EndTimeInvariantRule.class);
        focusRule = mock(SingleFocusRule.class);
        reestimationRule = mock(ReestimationConfirmationRule.class);
        progressRule = mock(ProgressRecalculationRule.class);
        habitRule = mock(HabitRecurrenceRule.class);
        processor = new CoreDomainChangeProcessor(
            endTimeRule, focusRule, reestimationRule, progressRule, habitRule);
    }

    @Test
    @DisplayName("applies the DR chain in order, threading each rule's output into the next")
    void applies_chain_in_order() {
        ExecutableSnapshot previous = ExecutableSnapshotBuilder.snapshot().status("TODO").build();
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().status("IN_PROGRESS").build();
        ExecutableSnapshot afterEndTime = ExecutableSnapshotBuilder.snapshot().name("a").build();
        ExecutableSnapshot afterFocus = ExecutableSnapshotBuilder.snapshot().name("b").build();
        ExecutableSnapshot afterReestimation = ExecutableSnapshotBuilder.snapshot().name("c").build();
        ExecutableSnapshot afterProgress = ExecutableSnapshotBuilder.snapshot().name("d").build();
        ExecutableSnapshot afterHabit = ExecutableSnapshotBuilder.snapshot().name("e").build();
        when(endTimeRule.apply(same(previous), same(merged), eq(ExternalSystem.NOTION)))
            .thenReturn(afterEndTime);
        when(focusRule.apply(same(previous), same(afterEndTime), eq(ExternalSystem.NOTION)))
            .thenReturn(afterFocus);
        when(reestimationRule.apply(same(previous), same(afterFocus), eq(ExternalSystem.NOTION)))
            .thenReturn(afterReestimation);
        when(progressRule.apply(same(previous), same(afterReestimation), eq(ExternalSystem.NOTION)))
            .thenReturn(afterProgress);
        when(habitRule.apply(same(previous), same(afterProgress), eq(ExternalSystem.NOTION)))
            .thenReturn(afterHabit);

        ExecutableSnapshot result = processor.process(previous, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(afterHabit);
        InOrder order = inOrder(endTimeRule, focusRule, reestimationRule, progressRule, habitRule);
        order.verify(endTimeRule).apply(any(), any(), any());
        order.verify(focusRule).apply(any(), any(), any());
        order.verify(reestimationRule).apply(any(), any(), any());
        order.verify(progressRule).apply(any(), any(), any());
        order.verify(habitRule).apply(any(), any(), any());
    }
}
