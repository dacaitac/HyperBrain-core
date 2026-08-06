package com.hyperbrain.core.application.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ContainmentCopyRule (ADR-039 hard copy of date + cycle)")
class ContainmentCopyRuleTest {

    private static final UUID CHILD_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000c1");
    private static final UUID BLOCK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final UUID CYCLE_ID = UUID.fromString("cccccccc-0000-0000-0000-0000000000e1");
    private static final OffsetDateTime BLOCK_START =
        OffsetDateTime.of(2026, 8, 6, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BLOCK_END = BLOCK_START.plusHours(1);

    private ExecutableStateRepository stateRepo;
    private OutboxRepository outboxRepo;
    private ContainmentCopyRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        rule = new ContainmentCopyRule(stateRepo, outboxRepo,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("a contained reminder child copies the container start + cycle (end cleared by DR-01)")
    void contained_reminder_copies_start_and_cycle() {
        when(stateRepo.findContainerSchedule(CHILD_ID, null, null)).thenReturn(Optional.of(
            new ContainerSchedule(BLOCK_ID, "Deep work", BLOCK_START, BLOCK_END, CYCLE_ID)));

        ExecutableSnapshot result = rule.apply(null, child("TASK", null, null, null),
            ExternalSystem.SYSTEM);

        assertThat(result.startTime()).isEqualTo(BLOCK_START);
        assertThat(result.endTime()).isNull();
        assertThat(result.cycleId()).isEqualTo(CYCLE_ID);
    }

    @Test
    @DisplayName("a contained event child copies the full window")
    void contained_event_copies_window() {
        when(stateRepo.findContainerSchedule(CHILD_ID, null, null)).thenReturn(Optional.of(
            new ContainerSchedule(BLOCK_ID, "Deep work", BLOCK_START, BLOCK_END, CYCLE_ID)));

        ExecutableSnapshot result = rule.apply(null, child("ACTIVITY", null, null, null),
            ExternalSystem.SYSTEM);

        assertThat(result.startTime()).isEqualTo(BLOCK_START);
        assertThat(result.endTime()).isEqualTo(BLOCK_END);
        assertThat(result.cycleId()).isEqualTo(CYCLE_ID);
    }

    @Test
    @DisplayName("an idempotent copy (values already equal) is an absolute no-op: no reassertion event")
    void idempotent_copy_stages_nothing() {
        when(stateRepo.findContainerSchedule(CHILD_ID, null, null)).thenReturn(Optional.of(
            new ContainerSchedule(BLOCK_ID, "Deep work", BLOCK_START, null, CYCLE_ID)));

        ExecutableSnapshot result = rule.apply(
            child("TASK", BLOCK_START, null, CYCLE_ID),
            child("TASK", BLOCK_START, null, CYCLE_ID),
            ExternalSystem.NOTION);

        assertThat(result.startTime()).isEqualTo(BLOCK_START);
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("an inbound human edit of a contained child's date is re-asserted with a visible Sync Note")
    void inbound_edit_reasserts_with_sync_note() {
        when(stateRepo.findContainerSchedule(CHILD_ID, null, null)).thenReturn(Optional.of(
            new ContainerSchedule(BLOCK_ID, "Deep work", BLOCK_START, null, CYCLE_ID)));
        ExecutableSnapshot previous = child("TASK", BLOCK_START, null, CYCLE_ID);
        // The user dragged the child to a different day in Notion.
        ExecutableSnapshot edited = child("TASK", BLOCK_START.plusDays(2), null, CYCLE_ID);

        ExecutableSnapshot result = rule.apply(previous, edited, ExternalSystem.NOTION);

        assertThat(result.startTime()).isEqualTo(BLOCK_START);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().payload())
            .contains("\"reflection\":\"CANONICAL_STATE\"")
            .contains("Deep work");
    }

    @Test
    @DisplayName("a container retime propagates the hard copy to N children, one outbox event per changed child")
    void container_retime_propagates_to_children() {
        UUID childA = UUID.fromString("dddddddd-0000-0000-0000-00000000000a");
        UUID childB = UUID.fromString("dddddddd-0000-0000-0000-00000000000b");
        when(stateRepo.copyScheduleToContained(eq(BLOCK_ID), any(), any(), any()))
            .thenReturn(List.of(childA, childB));
        ExecutableSnapshot previous = block(BLOCK_START, BLOCK_END);
        ExecutableSnapshot retimed = block(BLOCK_START.plusHours(3), BLOCK_END.plusHours(3));

        rule.apply(previous, retimed, ExternalSystem.SYSTEM);

        verify(stateRepo).copyScheduleToContained(BLOCK_ID,
            retimed.startTime(), retimed.endTime(), retimed.cycleId());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, times(2)).append(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(OutboxEvent::aggregateId)
            .containsExactly(childA.toString(), childB.toString());
    }

    @Test
    @DisplayName("an uncontained executable is left untouched")
    void uncontained_is_noop() {
        when(stateRepo.findContainerSchedule(CHILD_ID, null, null)).thenReturn(Optional.empty());

        ExecutableSnapshot merged = child("TASK", null, null, null);
        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(merged);
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("a system-generated snapshot is never a copy target")
    void system_generated_is_ignored() {
        ExecutableSnapshot snapshot = ExecutableSnapshotBuilder.snapshot()
            .id(CHILD_ID).type("TASK").systemGenerated(true).build();

        rule.apply(null, snapshot, ExternalSystem.SYSTEM);

        verifyNoInteractions(stateRepo, outboxRepo);
    }

    private static ExecutableSnapshot child(String type, OffsetDateTime start, OffsetDateTime end,
                                            UUID cycleId) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(CHILD_ID).type(type).status("TODO").startTime(start).endTime(end).cycleId(cycleId)
            .build();
    }

    private static ExecutableSnapshot block(OffsetDateTime start, OffsetDateTime end) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(BLOCK_ID).type("TIME_BLOCK").status("PLANNED")
            .startTime(start).endTime(end).cycleId(CYCLE_ID)
            .build();
    }
}
