package com.hyperbrain.core.application.rule;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("RecurrenceCloneRule (DR-04)")
class RecurrenceCloneRuleTest {

    private static final OffsetDateTime DUE = OffsetDateTime.of(2026, 7, 12, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = DUE.plusHours(1);

    private ExecutableStateRepository stateRepo;
    private OutboxRepository outboxRepo;
    private RecurrenceCloneRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        rule = new RecurrenceCloneRule(stateRepo, outboxRepo);
    }

    @Test
    @DisplayName("DONE transition with frequency: upserts clone with start_time + frequency days and emits ExecutableCreatedEvent")
    void done_with_frequency_clones_executable() {
        ExecutableSnapshot previous = habit("TODO");
        ExecutableSnapshot merged   = habit("DONE");

        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(merged);

        ArgumentCaptor<ExecutableSnapshot> cloneCaptor = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(stateRepo).upsertExecutable(cloneCaptor.capture());
        ExecutableSnapshot clone = cloneCaptor.getValue();

        assertThat(clone.id()).isNotEqualTo(merged.id());
        assertThat(clone.userId()).isEqualTo(merged.userId());
        assertThat(clone.name()).isEqualTo(merged.name());
        assertThat(clone.type()).isEqualTo(merged.type());
        assertThat(clone.status()).isEqualTo("TODO");
        assertThat(clone.frequency()).isEqualTo(10.0);
        assertThat(clone.startTime()).isEqualTo(DUE.plusDays(10));
        assertThat(clone.endTime()).isEqualTo(END.plusDays(10));
        assertThat(clone.priorityScore()).isNull();
        assertThat(clone.urgencyScore()).isNull();
        assertThat(clone.systemGenerated()).isFalse();

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.aggregateType()).isEqualTo("CORE_EXECUTABLE");
        assertThat(event.aggregateId()).isEqualTo(clone.id().toString());
        assertThat(event.eventType()).isEqualTo("ExecutableCreatedEvent");
        assertThat(event.sourceSystem()).isEqualTo("SYSTEM");
        assertThat(event.payload()).contains("\"local_id\":\"" + clone.id() + "\"");
        assertThat(event.payload()).contains("\"operation\":\"CREATED\"");
    }

    @Test
    @DisplayName("a contained source clones DETACHED (container_block_id = null) keeping its +frequency slot — no self-reasserting date corruption (core#59)")
    void contained_source_clones_detached_keeping_shifted_slot() {
        java.util.UUID todaysBlock = java.util.UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
        ExecutableSnapshot previous = habit("TODO");
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .id(previous.id()).type("HABIT").status("DONE").frequency(10.0)
            .startTime(DUE).endTime(END).containerBlockId(todaysBlock).build();

        rule.apply(previous, merged, ExternalSystem.NOTION);

        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(stateRepo).upsertExecutable(captor.capture());
        ExecutableSnapshot clone = captor.getValue();
        // Detached: no container survives to re-stamp today's window over the clone.
        assertThat(clone.containerBlockId()).isNull();
        // The shifted occurrence slot is preserved intact.
        assertThat(clone.startTime()).isEqualTo(DUE.plusDays(10));
        assertThat(clone.endTime()).isEqualTo(END.plusDays(10));
    }

    @Test
    @DisplayName("null end_time: clone has null end_time (no crash)")
    void null_end_time_produces_null_next_end() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("HABIT").status("DONE").frequency(10.0).startTime(DUE).build();

        rule.apply(ExecutableSnapshotBuilder.snapshot().type("HABIT").status("TODO").build(),
            merged, ExternalSystem.NOTION);

        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(stateRepo).upsertExecutable(captor.capture());
        assertThat(captor.getValue().endTime()).isNull();
    }

    @Test
    @DisplayName("already DONE on CREATE (previous=null): still clones — idempotency guard is on re-ingesting same state")
    void create_already_done_clones() {
        rule.apply(null, habit("DONE"), ExternalSystem.NOTION);

        verify(stateRepo).upsertExecutable(org.mockito.ArgumentMatchers.any());
        verify(outboxRepo).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("re-ingested DONE (previous=DONE): no clone — not a new transition")
    void already_done_previous_done_no_clone() {
        rule.apply(habit("DONE"), habit("DONE"), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("status not DONE: no clone")
    void not_done_no_clone() {
        rule.apply(habit("TODO"), habit("IN_PROGRESS"), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("frequency null: no clone")
    void null_frequency_no_clone() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("HABIT").status("DONE").startTime(DUE).build();

        rule.apply(ExecutableSnapshotBuilder.snapshot().type("HABIT").status("TODO").build(),
            merged, ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("frequency zero: no clone")
    void zero_frequency_no_clone() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("HABIT").status("DONE").frequency(0.0).startTime(DUE).build();

        rule.apply(ExecutableSnapshotBuilder.snapshot().type("HABIT").status("TODO").build(),
            merged, ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("ADR-040 D4: an AGENDA with a frequency closes WITHOUT cloning — the single exemption to DR-04")
    void agenda_never_clones_even_with_frequency() {
        ExecutableSnapshot previous = ExecutableSnapshotBuilder.snapshot()
            .type("AGENDA").status("TODO").frequency(7.0).startTime(DUE).endTime(END).build();
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("AGENDA").status("FAILED").frequency(7.0).startTime(DUE).endTime(END).build();

        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.SYSTEM);

        assertThat(result).isSameAs(merged);
        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("the AGENDA exemption is a property of the type, not of the sweep: a human closing one from Notion never clones either")
    void agenda_exemption_holds_on_the_ingestion_path() {
        ExecutableSnapshot previous = ExecutableSnapshotBuilder.snapshot()
            .type("AGENDA").status("TODO").frequency(1.0).startTime(DUE).build();
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("AGENDA").status("DONE").frequency(1.0).startTime(DUE).build();

        rule.apply(previous, merged, ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("an ACTIVITY with a frequency still clones on a FAILED closure — only AGENDA is exempt")
    void activity_with_frequency_still_clones_on_failure() {
        ExecutableSnapshot previous = ExecutableSnapshotBuilder.snapshot()
            .type("ACTIVITY").status("TODO").frequency(2.0).startTime(DUE).endTime(END).build();
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("ACTIVITY").status("FAILED").frequency(2.0).startTime(DUE).endTime(END).build();

        rule.apply(previous, merged, ExternalSystem.SYSTEM);

        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(stateRepo).upsertExecutable(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("ACTIVITY");
        assertThat(captor.getValue().startTime()).isEqualTo(DUE.plusDays(2));
        verify(outboxRepo).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("system_generated row: no clone")
    void system_generated_no_clone() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("HABIT").status("DONE").frequency(7.0).startTime(DUE).systemGenerated(true).build();

        rule.apply(null, merged, ExternalSystem.SYSTEM);

        verifyNoInteractions(stateRepo);
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("APPLE origin works the same — rule is origin-agnostic")
    void apple_origin_clones() {
        rule.apply(habit("TODO"), habit("DONE"), ExternalSystem.APPLE);

        verify(stateRepo).upsertExecutable(org.mockito.ArgumentMatchers.any());
        verify(outboxRepo).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("null start_time: clone has null start_time (no crash)")
    void null_start_time_produces_null_next_due() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("HABIT").status("DONE").frequency(7.0).build();

        rule.apply(ExecutableSnapshotBuilder.snapshot().type("HABIT").status("TODO").build(),
            merged, ExternalSystem.NOTION);

        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(stateRepo).upsertExecutable(captor.capture());
        assertThat(captor.getValue().startTime()).isNull();
    }

    // -- helpers --

    private ExecutableSnapshot habit(String status) {
        return ExecutableSnapshotBuilder.snapshot()
            .type("HABIT")
            .status(status)
            .frequency(10.0)
            .startTime(DUE)
            .endTime(END)
            .build();
    }
}
