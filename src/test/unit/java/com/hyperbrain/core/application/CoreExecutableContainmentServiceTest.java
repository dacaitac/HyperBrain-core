package com.hyperbrain.core.application;

import com.hyperbrain.core.domain.model.ContainmentOutcome;
import com.hyperbrain.core.domain.model.ContainmentRequest;
import com.hyperbrain.core.domain.model.ReleaseCause;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.hyperbrain.sync.support.ExecutableSnapshotBuilder.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CoreExecutableContainmentService — core's published containment operation (ADR-040 D11)")
class CoreExecutableContainmentServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final UUID BLOCK = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final UUID TASK = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID ACTIVITY = UUID.fromString("22222222-0000-0000-0000-00000000000b");
    private static final UUID CYCLE = UUID.fromString("cccccccc-0000-0000-0000-0000000000c1");
    /** 2026-08-07 08:30 Bogota. */
    private static final OffsetDateTime BLOCK_START =
        OffsetDateTime.of(2026, 8, 7, 13, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BLOCK_END =
        OffsetDateTime.of(2026, 8, 7, 15, 30, 0, 0, ZoneOffset.UTC);

    private ExecutableStateRepository stateRepo;
    private OutboxRepository outboxRepo;
    private CoreExecutableContainmentService service;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        service = new CoreExecutableContainmentService(stateRepo, outboxRepo);
    }

    @Test
    @DisplayName("containing a task assigns it, settles the hard copy once and announces what moved")
    void containing_assigns_copies_and_announces() {
        // Given
        givenBlock();
        when(stateRepo.findAllById(List.of(TASK))).thenReturn(List.of(task()));
        when(stateRepo.assignContainer(TASK, BLOCK, 60, 0)).thenReturn(true);
        when(stateRepo.copyScheduleToContained(BLOCK, BLOCK_START, BLOCK_END, CYCLE))
            .thenReturn(List.of(TASK));

        // When
        ContainmentOutcome outcome =
            service.contain(BLOCK, List.of(new ContainmentRequest(TASK, 60, 0)));

        // Then
        assertThat(outcome.contained()).containsExactly(TASK);
        assertThat(outcome.recopied()).containsExactly(TASK);
        assertThat(outcome.rejected()).isEmpty();
        // The member moved on both counts, yet it is announced exactly once.
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(events.capture());
        assertThat(events.getValue().aggregateId()).isEqualTo(TASK.toString());
        assertThat(events.getValue().eventType()).isEqualTo("ExecutableUpdatedEvent");
    }

    @Test
    @DisplayName("re-offering an unchanged membership is a no-op: nothing is written to the outbox")
    void an_unchanged_membership_emits_nothing() {
        // Given: the row is already exactly where the plan wants it.
        givenBlock();
        when(stateRepo.findAllById(List.of(TASK))).thenReturn(List.of(task()));
        when(stateRepo.assignContainer(TASK, BLOCK, 60, 0)).thenReturn(false);
        when(stateRepo.copyScheduleToContained(BLOCK, BLOCK_START, BLOCK_END, CYCLE))
            .thenReturn(List.of());

        // When
        ContainmentOutcome outcome =
            service.contain(BLOCK, List.of(new ContainmentRequest(TASK, 60, 0)));

        // Then
        assertThat(outcome.isNoOp()).isTrue();
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("an activity is refused, reported and never assigned — one bad member cannot abort a day")
    void an_ineligible_member_is_reported_not_thrown() {
        // Given
        givenBlock();
        ExecutableSnapshot activity = snapshot().id(ACTIVITY).type("ACTIVITY").build();
        when(stateRepo.findAllById(List.of(ACTIVITY, TASK))).thenReturn(List.of(activity, task()));
        when(stateRepo.assignContainer(TASK, BLOCK, 30, 1)).thenReturn(true);
        when(stateRepo.copyScheduleToContained(BLOCK, BLOCK_START, BLOCK_END, CYCLE))
            .thenReturn(List.of());

        // When
        ContainmentOutcome outcome = service.contain(BLOCK, List.of(
            new ContainmentRequest(ACTIVITY, 30, 0),
            new ContainmentRequest(TASK, 30, 1)));

        // Then
        assertThat(outcome.rejected()).containsExactly(ACTIVITY);
        assertThat(outcome.contained()).containsExactly(TASK);
        verify(stateRepo, never()).assignContainer(ACTIVITY, BLOCK, 30, 0);
    }

    @Test
    @DisplayName("a container that is not a block is refused outright: no row may impose its window")
    void a_non_block_container_is_rejected() {
        // Given
        when(stateRepo.findAllById(List.of(BLOCK)))
            .thenReturn(List.of(snapshot().id(BLOCK).type("TASK").build()));

        // Then
        assertThatThrownBy(() -> service.contain(BLOCK, List.of(new ContainmentRequest(TASK, 30, 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not a TIME_BLOCK");
    }

    @Test
    @DisplayName("a block is never a member of a block")
    void a_block_offered_as_a_member_is_rejected() {
        // Given
        givenBlock();
        UUID nested = UUID.fromString("dddddddd-0000-0000-0000-0000000000d1");
        when(stateRepo.findAllById(List.of(nested)))
            .thenReturn(List.of(snapshot().id(nested).type("TIME_BLOCK").build()));

        // Then
        assertThatThrownBy(() ->
            service.contain(BLOCK, List.of(new ContainmentRequest(nested, 30, 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never a member of a block");
    }

    @Test
    @DisplayName("a planner withdrawal releases each member and returns its hour to midnight")
    void planner_withdrawal_returns_the_hour_to_the_placeholder() {
        // Given: the member sits inside the block, so its 08:30 came from the block, not from the user.
        when(stateRepo.findContainedBy(BLOCK)).thenReturn(List.of(
            snapshot().id(TASK).type("TASK").startTime(BLOCK_START).containerBlockId(BLOCK).build()));
        when(stateRepo.clearContainer(TASK)).thenReturn(true);
        // 2026-08-07 00:00 Bogota, carrying the zone's own offset — the exact value the service writes.
        OffsetDateTime midnight =
            java.time.LocalDate.of(2026, 8, 7).atStartOfDay(ZONE).toOffsetDateTime();
        when(stateRepo.reschedule(TASK, midnight, null)).thenReturn(true);

        // When
        List<UUID> released = service.releaseMembers(BLOCK, ReleaseCause.PLANNER_WITHDRAWAL, ZONE);

        // Then
        assertThat(released).containsExactly(TASK);
        verify(stateRepo).reschedule(TASK, midnight, null);
        verify(outboxRepo).append(org.mockito.ArgumentMatchers.any(OutboxEvent.class));
    }

    @Test
    @DisplayName("a user detach releases the member but leaves its hour alone")
    void user_detach_keeps_the_hour() {
        // Given
        when(stateRepo.findContainedBy(BLOCK)).thenReturn(List.of(
            snapshot().id(TASK).type("TASK").startTime(BLOCK_START).containerBlockId(BLOCK).build()));
        when(stateRepo.clearContainer(TASK)).thenReturn(true);

        // When
        List<UUID> released = service.releaseMembers(BLOCK, ReleaseCause.USER_DETACH, ZONE);

        // Then
        assertThat(released).containsExactly(TASK);
        verify(stateRepo, never()).reschedule(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    private void givenBlock() {
        when(stateRepo.findAllById(List.of(BLOCK))).thenReturn(List.of(snapshot()
            .id(BLOCK).type("TIME_BLOCK").name("Deep work")
            .startTime(BLOCK_START).endTime(BLOCK_END).cycleId(CYCLE).build()));
    }

    private static ExecutableSnapshot task() {
        return snapshot().id(TASK).type("TASK").build();
    }
}
