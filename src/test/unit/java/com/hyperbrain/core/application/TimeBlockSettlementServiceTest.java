package com.hyperbrain.core.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.core.domain.model.TimeBlockExecutable;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.core.domain.port.out.TimeBlockExecutableRepository;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
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
import static org.mockito.Mockito.when;

@DisplayName("TimeBlockSettlementService (DR-08, ADR-039 executable model)")
class TimeBlockSettlementServiceTest {

    private static final UUID BLOCK_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000003");
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 7, 8, 14, 0, 0, 0, ZoneOffset.UTC);

    private TimeBlockExecutableRepository timeBlockRepo;
    private ExecutableStateRepository stateRepo;
    private OutboxRepository outboxRepo;
    private TimeBlockSettlementService service;

    @BeforeEach
    void setUp() {
        timeBlockRepo = mock(TimeBlockExecutableRepository.class);
        stateRepo = mock(ExecutableStateRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        service = new TimeBlockSettlementService(timeBlockRepo, stateRepo, outboxRepo,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("focus switch settles the block as DONE with gross minutes, imputes the window and emits the event")
    void focus_switch_settles_block() {
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_IN_PROGRESS, null);
        OffsetDateTime cutAt = START.plusMinutes(45);
        when(timeBlockRepo.settle(eq(BLOCK_ID), eq(TimeBlockExecutable.STATUS_DONE), eq(45), any()))
            .thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(BLOCK_ID, START, cutAt)).thenReturn(2);

        Optional<UUID> settled = service.settleOnFocusSwitch(block, cutAt);

        assertThat(settled).contains(BLOCK_ID);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("TimeBlockSettledEvent");
        assertThat(event.aggregateType()).isEqualTo("CORE_TIME_BLOCK");
        assertThat(event.sourceSystem()).isEqualTo("SYSTEM");
        assertThat(event.payload())
            .contains("\"final_status\":\"DONE\"")
            .contains("\"actual_duration_minutes\":45")
            .contains("\"imputed_subtask_count\":2");
    }

    @Test
    @DisplayName("a lost settlement race neither imputes nor emits")
    void lost_race_is_a_noop() {
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_IN_PROGRESS, null);
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(false);

        Optional<UUID> settled = service.settleOnFocusSwitch(block, START.plusMinutes(10));

        assertThat(settled).isEmpty();
        verify(stateRepo, never()).imputeCompletedSubtasks(any(), any(), any());
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("a settled block also stages the executable notification, so its mirrors stop showing it open")
    void settlement_stages_the_satellite_mirror() {
        OffsetDateTime end = START.plusMinutes(30);
        TimeBlockExecutable block = plannerBlock(TimeBlockExecutable.STATUS_PLANNED, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(any(), any(), any())).thenReturn(0);

        service.expireDueBlocks(end.plusMinutes(1));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, times(2)).append(captor.capture());
        // Disjoint by aggregate: the settlement event reaches no propagator, the executable one does.
        assertThat(captor.getAllValues()).extracting(OutboxEvent::aggregateType)
            .containsExactly("CORE_TIME_BLOCK", "CORE_EXECUTABLE");
        OutboxEvent mirror = captor.getAllValues().get(1);
        assertThat(mirror.eventType()).isEqualTo("ExecutableUpdatedEvent");
        assertThat(mirror.aggregateId()).isEqualTo(BLOCK_ID.toString());
        assertThat(mirror.sourceSystem()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("a FOCUS block is internal accounting: it settles without notifying any satellite")
    void focus_block_settlement_is_not_mirrored() {
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_IN_PROGRESS, null);
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(any(), any(), any())).thenReturn(0);

        service.settleOnFocusSwitch(block, START.plusMinutes(20));

        // Only the settlement event: notifying an unmapped FOCUS block would CREATE a spurious
        // calendar event instead of updating anything.
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().aggregateType()).isEqualTo("CORE_TIME_BLOCK");
    }

    @Test
    @DisplayName("a lost race stages neither event — settling twice notifies the satellites once")
    void lost_race_stages_no_mirror() {
        OffsetDateTime end = START.plusMinutes(30);
        TimeBlockExecutable block = plannerBlock(TimeBlockExecutable.STATUS_PLANNED, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(false);

        int settled = service.expireDueBlocks(end.plusMinutes(1));

        assertThat(settled).isZero();
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("ADR-040 D4: expiry settles IN_PROGRESS blocks as DONE with the planned window as actual")
    void expiry_settles_active_block() {
        OffsetDateTime end = START.plusMinutes(60);
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_IN_PROGRESS, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(eq(BLOCK_ID), eq(TimeBlockExecutable.STATUS_DONE), eq(60), any()))
            .thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(BLOCK_ID, START, end)).thenReturn(0);

        int settled = service.expireDueBlocks(end.plusMinutes(5));

        assertThat(settled).isEqualTo(1);
        verify(stateRepo).imputeCompletedSubtasks(BLOCK_ID, START, end);
    }

    @Test
    @DisplayName("ADR-040 D4: a block that merely elapsed settles as DONE — never FAILED — with a null actual duration when it never ran")
    void expiry_settles_planned_block_with_null_actual() {
        OffsetDateTime end = START.plusMinutes(30);
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_PLANNED, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(eq(BLOCK_ID), eq(TimeBlockExecutable.STATUS_DONE),
            eq((Integer) null), any())).thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(any(), any(), any())).thenReturn(0);

        int settled = service.expireDueBlocks(end.plusHours(1));

        assertThat(settled).isEqualTo(1);
        verify(timeBlockRepo, never())
            .settle(any(), eq(TimeBlockExecutable.STATUS_FAILED), any(), any());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().payload())
            .contains("\"actual_duration_minutes\":null")
            .contains("\"final_status\":\"DONE\"");
    }

    @Test
    @DisplayName("a settled block feeds no achievement: the sweep touches no streak and stamps no completion clock on the block itself")
    void settled_block_never_feeds_a_streak() {
        OffsetDateTime end = START.plusMinutes(30);
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_IN_PROGRESS, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(any(), any(), any())).thenReturn(0);

        service.expireDueBlocks(end.plusMinutes(1));

        // "Closed" and "achieved" are separate predicates: a container of time that elapsed is not
        // an achievement, so DONE here must never reach the streak pair or the closure clock.
        verify(stateRepo, never()).recordAchievedStreak(any());
        verify(stateRepo, never()).resetStreak(any());
        verify(stateRepo, never()).markCompleted(any(), any());
    }

    private static TimeBlockExecutable block(String status, OffsetDateTime endTime) {
        return new TimeBlockExecutable(BLOCK_ID, USER_ID, TASK_ID, START, endTime,
            status, TimeBlockExecutable.ORIGIN_FOCUS, null, null);
    }

    /** A mirrored container (PLANNER/USER origin), as opposed to FOCUS accounting. */
    private static TimeBlockExecutable plannerBlock(String status, OffsetDateTime endTime) {
        return new TimeBlockExecutable(BLOCK_ID, USER_ID, null, START, endTime,
            status, TimeBlockExecutable.ORIGIN_PLANNER, null, null);
    }
}
