package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.CompletionOutcomeRule;
import com.hyperbrain.core.application.rule.RecurrenceCloneRule;
import com.hyperbrain.core.domain.model.OverdueSweepReport;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("OverdueSweepService — the day-close sweep by type (ADR-040 D4)")
class OverdueSweepServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ROW_ID = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID BLOCK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    /** The sweep opens 2026-08-07 and closes 2026-08-06. */
    private static final LocalDate REFERENCE_DAY = LocalDate.of(2026, 8, 7);
    /** Yesterday 14:30 local (Bogota = UTC-5). */
    private static final OffsetDateTime YESTERDAY_AFTERNOON =
        OffsetDateTime.of(2026, 8, 6, 19, 30, 0, 0, ZoneOffset.UTC);

    private ExecutableStateRepository stateRepo;
    private CompletionOutcomeRule completionOutcomeRule;
    private RecurrenceCloneRule recurrenceCloneRule;
    private OutboxRepository outboxRepo;
    private OverdueSweepService service;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        completionOutcomeRule = mock(CompletionOutcomeRule.class);
        recurrenceCloneRule = mock(RecurrenceCloneRule.class);
        outboxRepo = mock(OutboxRepository.class);
        service = new OverdueSweepService(
            stateRepo, completionOutcomeRule, recurrenceCloneRule, outboxRepo);
    }

    // ── Closing as a sanctioned miss ──────────────────────────────────────────

    @Test
    @DisplayName("an expired HABIT closes as FAILED, runs the closure outcome and the recurrence clone, and notifies the satellites")
    void expired_habit_closes_as_failed() {
        ExecutableSnapshot habit = candidate("HABIT", YESTERDAY_AFTERNOON);
        givenCandidates(habit);
        when(stateRepo.closeAsFailed(ROW_ID)).thenReturn(true);

        OverdueSweepReport report = service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        assertThat(report.closedAsFailed()).isEqualTo(1);
        assertThat(report.rescheduled()).isZero();
        // The two links run in the chain's own order, on the FAILED state, with a SYSTEM origin.
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(completionOutcomeRule).apply(eq(habit), captor.capture(), eq(ExternalSystem.SYSTEM));
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().id()).isEqualTo(ROW_ID);
        verify(recurrenceCloneRule).apply(eq(habit), any(), eq(ExternalSystem.SYSTEM));
        assertThat(appendedEvent().eventType()).isEqualTo("ExecutableUpdatedEvent");
        assertThat(appendedEvent().sourceSystem()).isEqualTo("SYSTEM");
        assertThat(appendedEvent().aggregateId()).isEqualTo(ROW_ID.toString());
    }

    @Test
    @DisplayName("a lost closure race performs no side effect at all — no streak, no clone, no event")
    void lost_closure_race_is_a_noop() {
        givenCandidates(candidate("LEAD_MEASURE", YESTERDAY_AFTERNOON));
        when(stateRepo.closeAsFailed(ROW_ID)).thenReturn(false);

        OverdueSweepReport report = service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        assertThat(report.isEmpty()).isTrue();
        verifyNoInteractions(completionOutcomeRule);
        verifyNoInteractions(recurrenceCloneRule);
        verifyNoInteractions(outboxRepo);
    }

    // ── Re-dating a TASK ──────────────────────────────────────────────────────

    @Test
    @DisplayName("an uncontained TASK keeps its own time of day and only shifts date — landing ON the reference day")
    void uncontained_task_keeps_its_hour() {
        givenCandidates(candidate("TASK", YESTERDAY_AFTERNOON));
        when(stateRepo.clearContainer(ROW_ID)).thenReturn(false);
        when(stateRepo.reschedule(eq(ROW_ID), any(), any())).thenReturn(true);
        when(stateRepo.copyScheduleToContained(any(), any(), any(), any())).thenReturn(List.of());

        OverdueSweepReport report = service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        assertThat(report.rescheduled()).isEqualTo(1);
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(stateRepo).reschedule(eq(ROW_ID), start.capture(), eq(null));
        assertThat(start.getValue()).isEqualTo(YESTERDAY_AFTERNOON.plusDays(1));
        assertThat(start.getValue().atZoneSameInstant(ZONE).toLocalDate()).isEqualTo(REFERENCE_DAY);
    }

    @Test
    @DisplayName("a five-day-stale TASK also lands ON the reference day — it never walks one day per run")
    void stale_task_lands_on_the_reference_day() {
        givenCandidates(candidate("TASK", YESTERDAY_AFTERNOON.minusDays(4)));
        when(stateRepo.clearContainer(ROW_ID)).thenReturn(false);
        when(stateRepo.reschedule(eq(ROW_ID), any(), any())).thenReturn(true);
        when(stateRepo.copyScheduleToContained(any(), any(), any(), any())).thenReturn(List.of());

        service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(stateRepo).reschedule(eq(ROW_ID), start.capture(), eq(null));
        assertThat(start.getValue().atZoneSameInstant(ZONE).toLocalDate()).isEqualTo(REFERENCE_DAY);
        assertThat(start.getValue().atZoneSameInstant(ZONE).toLocalTime())
            .isEqualTo(YESTERDAY_AFTERNOON.atZoneSameInstant(ZONE).toLocalTime());
    }

    @Test
    @DisplayName("a TASK contained in yesterday's block is DETACHED FIRST and lands on the new day's midnight placeholder")
    void contained_task_is_detached_before_being_re_dated() {
        ExecutableSnapshot task = ExecutableSnapshotBuilder.snapshot()
            .id(ROW_ID).userId(USER_ID).type("TASK").status("TODO")
            .startTime(YESTERDAY_AFTERNOON).containerBlockId(BLOCK_ID).build();
        givenCandidates(task);
        when(stateRepo.clearContainer(ROW_ID)).thenReturn(true);
        when(stateRepo.reschedule(eq(ROW_ID), any(), any())).thenReturn(true);
        when(stateRepo.copyScheduleToContained(any(), any(), any(), any())).thenReturn(List.of());

        service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        var inOrder = org.mockito.Mockito.inOrder(stateRepo);
        inOrder.verify(stateRepo).clearContainer(ROW_ID);
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        inOrder.verify(stateRepo).reschedule(eq(ROW_ID), start.capture(), eq(null));
        // The block owned that hour, not the user: the day survives, the hour returns to midnight.
        assertThat(start.getValue()).isEqualTo(REFERENCE_DAY.atStartOfDay(ZONE).toOffsetDateTime());
    }

    @Test
    @DisplayName("the re-dated window is pushed down the hard-copy chain, one event per actually-changed descendant")
    void re_dating_propagates_to_descendants() {
        UUID subtask = UUID.fromString("55555555-0000-0000-0000-000000000005");
        UUID cycleId = UUID.fromString("66666666-0000-0000-0000-000000000006");
        givenCandidates(ExecutableSnapshotBuilder.snapshot()
            .id(ROW_ID).userId(USER_ID).cycleId(cycleId).type("TASK").status("TODO")
            .startTime(YESTERDAY_AFTERNOON).build());
        when(stateRepo.clearContainer(ROW_ID)).thenReturn(false);
        when(stateRepo.reschedule(eq(ROW_ID), any(), any())).thenReturn(true);
        when(stateRepo.copyScheduleToContained(eq(ROW_ID), any(), eq(null), eq(cycleId)))
            .thenReturn(List.of(subtask));

        service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, org.mockito.Mockito.times(2)).append(events.capture());
        assertThat(events.getAllValues()).extracting(OutboxEvent::aggregateId)
            .containsExactly(subtask.toString(), ROW_ID.toString());
    }

    @Test
    @DisplayName("a TASK already on the reference day is not moved and emits nothing — the sweep is idempotent")
    void unchanged_task_emits_nothing() {
        givenCandidates(candidate("TASK", YESTERDAY_AFTERNOON));
        when(stateRepo.clearContainer(ROW_ID)).thenReturn(false);
        when(stateRepo.reschedule(eq(ROW_ID), any(), any())).thenReturn(false);

        OverdueSweepReport report = service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        assertThat(report.isEmpty()).isTrue();
        verify(stateRepo, never()).copyScheduleToContained(any(), any(), any(), any());
        verifyNoInteractions(outboxRepo);
    }

    // ── Clearing the date of a purchase ───────────────────────────────────────

    @Test
    @DisplayName("an expired BUYING loses its date entirely and returns to the dateless bag")
    void expired_buying_loses_its_date() {
        givenCandidates(candidate("BUYING", YESTERDAY_AFTERNOON));
        when(stateRepo.reschedule(ROW_ID, null, null)).thenReturn(true);

        OverdueSweepReport report = service.sweep(USER_ID, REFERENCE_DAY, ZONE);

        assertThat(report.dateCleared()).isEqualTo(1);
        // Detached first, so the containment hard copy cannot stamp the block's date straight back.
        var inOrder = org.mockito.Mockito.inOrder(stateRepo);
        inOrder.verify(stateRepo).clearContainer(ROW_ID);
        inOrder.verify(stateRepo).reschedule(ROW_ID, null, null);
        verify(stateRepo, never()).closeAsFailed(any());
        assertThat(appendedEvent().eventType()).isEqualTo("ExecutableUpdatedEvent");
    }

    @Test
    @DisplayName("an empty day is a no-op: nothing is read twice, nothing is emitted")
    void empty_sweep_is_a_noop() {
        when(stateRepo.findOverdue(any(), any(), any(), any())).thenReturn(List.of());

        assertThat(service.sweep(USER_ID, REFERENCE_DAY, ZONE).isEmpty()).isTrue();
        verifyNoInteractions(outboxRepo);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void givenCandidates(ExecutableSnapshot... candidates) {
        when(stateRepo.findOverdue(eq(USER_ID), any(), eq(REFERENCE_DAY), eq(ZONE)))
            .thenReturn(List.of(candidates));
    }

    private OutboxEvent appendedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        return captor.getValue();
    }

    private static ExecutableSnapshot candidate(String type, OffsetDateTime startTime) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(ROW_ID).userId(USER_ID).type(type).status("TODO").startTime(startTime).build();
    }
}
