package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.core.application.TimeBlockSettlementService;
import com.hyperbrain.core.domain.model.FocusCandidate;
import com.hyperbrain.core.domain.model.SnapshotSubtask;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("SingleFocusRule (DR-05 + DR-06)")
class SingleFocusRuleTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW_FOCUS_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CUT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID BLOCK_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private ExecutableStateRepository stateRepo;
    private TimeBlockRepository timeBlockRepo;
    private TimeBlockSettlementService settlementService;
    private OutboxRepository outboxRepo;
    private SingleFocusRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        timeBlockRepo = mock(TimeBlockRepository.class);
        settlementService = mock(TimeBlockSettlementService.class);
        outboxRepo = mock(OutboxRepository.class);
        rule = new SingleFocusRule(stateRepo, timeBlockRepo, settlementService, outboxRepo,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("cuts the executing focus: settles its block, freezes a snapshot with the original labels and empties the effort")
    void cuts_executing_focus() {
        ExecutableSnapshot merged = inProgress("TASK");
        FocusCandidate candidate = new FocusCandidate(
            CUT_ID, USER_ID, "Deep work", 3.5, true, 4, 3, 5, 120);
        TimeBlock activeBlock = new TimeBlock(BLOCK_ID, CUT_ID,
            OffsetDateTime.now().minusMinutes(45), null,
            TimeBlockStatus.ACTIVE, TimeBlockOrigin.FOCUS, null, null, null, null);
        when(stateRepo.isSystemGenerated(NEW_FOCUS_ID)).thenReturn(false);
        when(stateRepo.findActiveFocus(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of(candidate));
        when(timeBlockRepo.findActiveBlock(CUT_ID)).thenReturn(Optional.of(activeBlock));
        when(timeBlockRepo.findActiveBlock(NEW_FOCUS_ID)).thenReturn(Optional.empty());
        when(settlementService.settleOnFocusSwitch(eq(activeBlock), any()))
            .thenReturn(Optional.of(BLOCK_ID));

        ExecutableSnapshot result = rule.apply(todo("TASK"), merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(merged);
        ArgumentCaptor<SnapshotSubtask> snapshotCaptor = ArgumentCaptor.forClass(SnapshotSubtask.class);
        verify(stateRepo).insertSystemSnapshot(snapshotCaptor.capture());
        SnapshotSubtask snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.parentId()).isEqualTo(CUT_ID);
        assertThat(snapshot.name()).isEqualTo("Deep work");
        assertThat(snapshot.effortScore()).isEqualTo(3.5);
        assertThat(snapshot.isImportant()).isTrue();
        assertThat(snapshot.energyDrain()).isEqualTo(4);
        assertThat(snapshot.mentalLoad()).isEqualTo(3);
        assertThat(snapshot.impact()).isEqualTo(5);
        assertThat(snapshot.estimatedMinutes()).isEqualTo(120);
        assertThat(snapshot.windowStart()).isEqualTo(activeBlock.dateStart());
        assertThat(snapshot.description()).startsWith("[focus] ");
        verify(stateRepo).clearEffortForReestimation(CUT_ID);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, org.mockito.Mockito.times(3)).append(outboxCaptor.capture());
        List<OutboxEvent> events = outboxCaptor.getAllValues();
        assertThat(events).extracting(OutboxEvent::eventType).containsExactly(
            "ExecutableCreatedEvent", "ExecutableUpdatedEvent", "FocusSwitchedEvent");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.sourceSystem()).isEqualTo("SYSTEM");
            assertThat(e.aggregateType()).isEqualTo("CORE_EXECUTABLE");
        });
        assertThat(events.get(0).aggregateId()).isEqualTo(snapshot.id().toString());
        assertThat(events.get(1).aggregateId()).isEqualTo(CUT_ID.toString());
        assertThat(events.get(2).payload())
            .contains("\"previous_executable_id\":\"" + CUT_ID + "\"")
            .contains("\"new_executable_id\":\"" + NEW_FOCUS_ID + "\"")
            .contains("\"settled_block_id\":\"" + BLOCK_ID + "\"");

        verify(timeBlockRepo).insert(any(TimeBlock.class));
    }

    @Test
    @DisplayName("opens an ACTIVE/FOCUS block for the new focus when it has none")
    void opens_focus_block_for_new_focus() {
        when(stateRepo.isSystemGenerated(NEW_FOCUS_ID)).thenReturn(false);
        when(stateRepo.findActiveFocus(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of());
        when(stateRepo.findLegacyInProgress(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of());
        when(timeBlockRepo.findActiveBlock(NEW_FOCUS_ID)).thenReturn(Optional.empty());

        rule.apply(todo("TASK"), inProgress("TASK"), ExternalSystem.NOTION);

        ArgumentCaptor<TimeBlock> blockCaptor = ArgumentCaptor.forClass(TimeBlock.class);
        verify(timeBlockRepo).insert(blockCaptor.capture());
        TimeBlock block = blockCaptor.getValue();
        assertThat(block.executableId()).isEqualTo(NEW_FOCUS_ID);
        assertThat(block.status()).isEqualTo(TimeBlockStatus.ACTIVE);
        assertThat(block.origin()).isEqualTo(TimeBlockOrigin.FOCUS);
        assertThat(block.dateEnd()).isNull();
    }

    @Test
    @DisplayName("a task born IN_PROGRESS (CREATE) cuts the previous focus but defers its own block")
    void create_in_progress_cuts_but_defers_block() {
        when(stateRepo.isSystemGenerated(NEW_FOCUS_ID)).thenReturn(false);
        when(stateRepo.findActiveFocus(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of());
        when(stateRepo.findLegacyInProgress(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of());

        rule.apply(null, inProgress("TASK"), ExternalSystem.NOTION);

        verify(timeBlockRepo, never()).insert(any());
    }

    @Test
    @DisplayName("legacy blockless focus is cut with a punctual window and no settled block")
    void legacy_focus_cut_with_punctual_window() {
        FocusCandidate legacy = new FocusCandidate(
            CUT_ID, USER_ID, "Old task", null, false, null, null, null, null);
        when(stateRepo.isSystemGenerated(NEW_FOCUS_ID)).thenReturn(false);
        when(stateRepo.findActiveFocus(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of());
        when(stateRepo.findLegacyInProgress(USER_ID, NEW_FOCUS_ID)).thenReturn(List.of(legacy));
        when(timeBlockRepo.findActiveBlock(CUT_ID)).thenReturn(Optional.empty());
        when(timeBlockRepo.findActiveBlock(NEW_FOCUS_ID)).thenReturn(Optional.empty());

        rule.apply(todo("TASK"), inProgress("TASK"), ExternalSystem.NOTION);

        ArgumentCaptor<SnapshotSubtask> snapshotCaptor = ArgumentCaptor.forClass(SnapshotSubtask.class);
        verify(stateRepo).insertSystemSnapshot(snapshotCaptor.capture());
        SnapshotSubtask snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.windowStart()).isEqualTo(snapshot.completedAt());
        assertThat(snapshot.description()).contains("(0 min)");
        verify(settlementService, never()).settleOnFocusSwitch(any(), any());
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, org.mockito.Mockito.times(3)).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getAllValues().get(2).payload())
            .contains("\"settled_block_id\":null");
    }

    @Test
    @DisplayName("AGENDA executables never take the focus (walls, not intentions)")
    void agenda_does_not_cut() {
        ExecutableSnapshot result = rule.apply(null, inProgress("AGENDA"), ExternalSystem.NOTION);

        assertThat(result.type()).isEqualTo("AGENDA");
        verifyNoInteractions(stateRepo, timeBlockRepo, settlementService, outboxRepo);
    }

    @Test
    @DisplayName("no transition (already IN_PROGRESS) is a no-op")
    void already_in_progress_is_noop() {
        rule.apply(inProgress("TASK"), inProgress("TASK"), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo, timeBlockRepo, settlementService, outboxRepo);
    }

    @Test
    @DisplayName("a system-generated snapshot echo never triggers the cut")
    void system_generated_echo_is_noop() {
        when(stateRepo.isSystemGenerated(NEW_FOCUS_ID)).thenReturn(true);

        rule.apply(null, inProgress("TASK"), ExternalSystem.NOTION);

        verify(stateRepo, never()).findActiveFocus(any(), any());
        verifyNoInteractions(timeBlockRepo, settlementService, outboxRepo);
    }

    private static ExecutableSnapshot inProgress(String type) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(NEW_FOCUS_ID).userId(USER_ID).type(type).status("IN_PROGRESS")
            .build();
    }

    private static ExecutableSnapshot todo(String type) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(NEW_FOCUS_ID).userId(USER_ID).type(type).status("TODO")
            .build();
    }
}
