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
    @DisplayName("expiry settles IN_PROGRESS blocks as FAILED with the planned window as actual")
    void expiry_settles_active_block() {
        OffsetDateTime end = START.plusMinutes(60);
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_IN_PROGRESS, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(eq(BLOCK_ID), eq(TimeBlockExecutable.STATUS_FAILED), eq(60), any()))
            .thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(BLOCK_ID, START, end)).thenReturn(0);

        int settled = service.expireDueBlocks(end.plusMinutes(5));

        assertThat(settled).isEqualTo(1);
        verify(stateRepo).imputeCompletedSubtasks(BLOCK_ID, START, end);
    }

    @Test
    @DisplayName("expiry settles never-executed PLANNED blocks as FAILED with a null actual duration")
    void expiry_settles_planned_block_with_null_actual() {
        OffsetDateTime end = START.plusMinutes(30);
        TimeBlockExecutable block = block(TimeBlockExecutable.STATUS_PLANNED, end);
        when(timeBlockRepo.lockOpenExpired(any())).thenReturn(List.of(block));
        when(timeBlockRepo.settle(eq(BLOCK_ID), eq(TimeBlockExecutable.STATUS_FAILED),
            eq((Integer) null), any())).thenReturn(true);
        when(stateRepo.imputeCompletedSubtasks(any(), any(), any())).thenReturn(0);

        int settled = service.expireDueBlocks(end.plusHours(1));

        assertThat(settled).isEqualTo(1);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().payload())
            .contains("\"actual_duration_minutes\":null")
            .contains("\"final_status\":\"FAILED\"");
    }

    private static TimeBlockExecutable block(String status, OffsetDateTime endTime) {
        return new TimeBlockExecutable(BLOCK_ID, USER_ID, TASK_ID, START, endTime,
            status, TimeBlockExecutable.ORIGIN_FOCUS, null, null);
    }
}
