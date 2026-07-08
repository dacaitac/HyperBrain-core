package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.core.domain.model.SubtaskCounts;
import com.hyperbrain.core.domain.model.TimeBlock;
import com.hyperbrain.core.domain.model.TimeBlockOrigin;
import com.hyperbrain.core.domain.model.TimeBlockStatus;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.core.domain.port.out.TimeBlockRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ProgressRecalculationRule (DR-07)")
class ProgressRecalculationRuleTest {

    private static final UUID PARENT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000010");
    private static final UUID SUBTASK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000020");
    private static final UUID BLOCK_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000030");

    private ExecutableStateRepository stateRepo;
    private TimeBlockRepository timeBlockRepo;
    private OutboxRepository outboxRepo;
    private ProgressRecalculationRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        timeBlockRepo = mock(TimeBlockRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        rule = new ProgressRecalculationRule(stateRepo, timeBlockRepo, outboxRepo,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("subtask completed: recomputes parent progress, stamps completion, imputes to the covering block and emits the event")
    void completion_recalculates_and_imputes() {
        ExecutableSnapshot merged = subtask("DONE");
        when(stateRepo.isSystemGenerated(SUBTASK_ID)).thenReturn(false);
        when(stateRepo.countUserSubtasks(PARENT_ID, SUBTASK_ID))
            .thenReturn(new SubtaskCounts(3, 2));
        when(timeBlockRepo.findActiveBlock(PARENT_ID)).thenReturn(Optional.of(activeBlock()));

        rule.apply(subtask("TODO"), merged, ExternalSystem.NOTION);

        verify(stateRepo).updateProgress(PARENT_ID, 0.75);
        verify(stateRepo).markCompleted(eq(SUBTASK_ID), any(OffsetDateTime.class));
        verify(stateRepo).imputeToBlock(SUBTASK_ID, BLOCK_ID);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("SubtaskCompletedEvent");
        assertThat(event.sourceSystem()).isEqualTo("SYSTEM");
        assertThat(event.payload())
            .contains("\"subtask_id\":\"" + SUBTASK_ID + "\"")
            .contains("\"parent_id\":\"" + PARENT_ID + "\"")
            .contains("\"imputed_time_block_id\":\"" + BLOCK_ID + "\"")
            .contains("\"parent_progress\":0.75");
    }

    @Test
    @DisplayName("completion outside any block is unplanned work: no imputation, event carries null block")
    void completion_without_block_is_unplanned() {
        ExecutableSnapshot merged = subtask("DONE");
        when(stateRepo.isSystemGenerated(SUBTASK_ID)).thenReturn(false);
        when(stateRepo.countUserSubtasks(PARENT_ID, SUBTASK_ID))
            .thenReturn(new SubtaskCounts(0, 0));
        when(timeBlockRepo.findActiveBlock(PARENT_ID)).thenReturn(Optional.empty());

        rule.apply(subtask("TODO"), merged, ExternalSystem.NOTION);

        verify(stateRepo).updateProgress(PARENT_ID, 1.0);
        verify(stateRepo, never()).imputeToBlock(any(), any());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().payload()).contains("\"imputed_time_block_id\":null");
    }

    @Test
    @DisplayName("un-completing recomputes progress and reverts the imputation")
    void uncompleting_reverts_imputation() {
        ExecutableSnapshot merged = subtask("IN_PROGRESS");
        when(stateRepo.isSystemGenerated(SUBTASK_ID)).thenReturn(false);
        when(stateRepo.countUserSubtasks(PARENT_ID, SUBTASK_ID))
            .thenReturn(new SubtaskCounts(2, 1));

        rule.apply(subtask("DONE"), merged, ExternalSystem.NOTION);

        verify(stateRepo).updateProgress(PARENT_ID, 1.0 / 3);
        verify(stateRepo).clearImputation(SUBTASK_ID);
        verify(stateRepo, never()).markCompleted(any(), any());
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("executables without a parent are ignored")
    void no_parent_is_noop() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot().status("DONE").build();

        rule.apply(null, merged, ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo, timeBlockRepo, outboxRepo);
    }

    @Test
    @DisplayName("no status change means no recalculation")
    void unchanged_status_is_noop() {
        rule.apply(subtask("DONE"), subtask("DONE"), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo, timeBlockRepo, outboxRepo);
    }

    @Test
    @DisplayName("system-generated snapshots never count as progress")
    void system_generated_is_excluded() {
        when(stateRepo.isSystemGenerated(SUBTASK_ID)).thenReturn(true);

        rule.apply(subtask("TODO"), subtask("DONE"), ExternalSystem.NOTION);

        verify(stateRepo, never()).updateProgress(any(), any());
        verifyNoInteractions(timeBlockRepo, outboxRepo);
    }

    private static ExecutableSnapshot subtask(String status) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(SUBTASK_ID).parentId(PARENT_ID).status(status)
            .build();
    }

    private static TimeBlock activeBlock() {
        return new TimeBlock(BLOCK_ID, PARENT_ID, OffsetDateTime.now().minusMinutes(30), null,
            TimeBlockStatus.ACTIVE, TimeBlockOrigin.FOCUS, null, null, null, null);
    }
}
