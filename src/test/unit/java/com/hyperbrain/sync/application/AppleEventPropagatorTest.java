package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import com.hyperbrain.sync.infrastructure.WriteCommandWireMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AppleEventPropagator")
class AppleEventPropagatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 7, 6, 9, 0, 0, 0, ZoneOffset.UTC);

    private CoreExecutableRepository executableRepo;
    private SyncMappingRepository syncMappingRepo;
    private WriteCommandLogRepository commandLogRepo;
    private WriteCommandPublisher commandPublisher;
    private AppleEventPropagator service;

    @BeforeEach
    void setUp() {
        executableRepo = mock(CoreExecutableRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        commandLogRepo = mock(WriteCommandLogRepository.class);
        commandPublisher = mock(WriteCommandPublisher.class);
        WriteCommandWireMapper wireMapper =
            new WriteCommandWireMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        service = new AppleEventPropagator(
            executableRepo, syncMappingRepo, commandLogRepo, commandPublisher, wireMapper);
    }

    @Test
    @DisplayName("propagator contract: target is APPLE and only executables from known origins are eligible (CA-12)")
    void should_propagate_only_executables_from_known_origins() {
        // Then — the drain excludes origin == target before consulting shouldPropagate (CA-10)
        assertThat(service.target()).isEqualTo(ExternalSystem.APPLE);
        assertThat(service.shouldPropagate(ExternalSystem.NOTION, SyncedEntityType.EXECUTABLE)).isTrue();
        assertThat(service.shouldPropagate(ExternalSystem.SYSTEM, SyncedEntityType.EXECUTABLE)).isTrue();
        assertThat(service.shouldPropagate(ExternalSystem.UNKNOWN, SyncedEntityType.EXECUTABLE)).isFalse();
        assertThat(service.shouldPropagate(ExternalSystem.NOTION, SyncedEntityType.CYCLE)).isFalse();
        assertThat(service.shouldPropagate(ExternalSystem.SYSTEM, SyncedEntityType.OTHER)).isFalse();
    }

    @Test
    @DisplayName("events of other aggregates or unknown event types are ignored")
    void unrelated_events_are_ignored() {
        // When
        service.propagate(event("FINANCE_TRANSACTION", "FinanceTransactionLoggedEvent", "SYSTEM"));
        service.propagate(event("CORE_EXECUTABLE", "SomethingElseEvent", "SYSTEM"));

        // Then
        verifyNoInteractions(executableRepo, syncMappingRepo, commandLogRepo, commandPublisher);
    }

    @Test
    @DisplayName("CREATE: unmapped executable emits CREATED with null entity_id and logs pending row (CA-3)")
    void unmapped_executable_emits_created() {
        // Given
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(task("TODO")));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID)).thenReturn(Optional.empty());
        when(commandLogRepo.findPendingCreateByLocalId(LOCAL_ID)).thenReturn(Optional.empty());

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM"));

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(captor.capture(), eq(LOCAL_ID.toString()));
        WriteCommand command = captor.getValue();
        assertThat(command.operation()).isEqualTo(Operation.CREATED);
        assertThat(command.commandType()).isEqualTo(CommandType.REMINDER);
        assertThat(command.entityId()).isNull();

        ArgumentCaptor<PendingWriteCommand> pending = ArgumentCaptor.forClass(PendingWriteCommand.class);
        verify(commandLogRepo).upsertPending(pending.capture());
        assertThat(pending.getValue().commandId()).isEqualTo(command.commandId());
        assertThat(pending.getValue().localId()).isEqualTo(LOCAL_ID);
        assertThat(pending.getValue().userId()).isEqualTo(USER_ID);
        assertThat(pending.getValue().payloadJson()).contains("\"title\":\"Buy groceries\"");
    }

    @Test
    @DisplayName("UPDATE: mapped executable emits UPDATED against the EventKit id (CA-2, CA-6)")
    void mapped_executable_emits_updated() {
        // Given
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(task("DONE")));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID))
            .thenReturn(Optional.of(mapping("EK-1")));

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "NOTION"));

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(captor.capture(), eq("EK-1"));
        assertThat(captor.getValue().operation()).isEqualTo(Operation.UPDATED);
        assertThat(captor.getValue().entityId()).isEqualTo("EK-1");
    }

    @Test
    @DisplayName("TaskCompletedEvent on a mapped task emits UPDATED with completed=true (scenario 3)")
    void task_completed_emits_update() {
        // Given
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(task("DONE")));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID))
            .thenReturn(Optional.of(mapping("EK-1")));

        // When
        service.propagate(event("TASK", "TaskCompletedEvent", "SYSTEM"));

        // Then
        ArgumentCaptor<PendingWriteCommand> pending = ArgumentCaptor.forClass(PendingWriteCommand.class);
        verify(commandLogRepo).upsertPending(pending.capture());
        assertThat(pending.getValue().operation()).isEqualTo(Operation.UPDATED);
        assertThat(pending.getValue().payloadJson()).contains("\"completed\":true");
    }

    @Test
    @DisplayName("AGENDA executable is rejected before emitting (CA-1, CA-19)")
    void agenda_executable_is_rejected() {
        // Given
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(executable("AGENDA", "TODO")));

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM"));

        // Then
        verify(commandPublisher, never()).publish(any(), anyString());
        verifyNoInteractions(commandLogRepo, syncMappingRepo);
    }

    @Test
    @DisplayName("CREATE already in flight for the executable: a different event does not emit a second CREATE")
    void pending_create_blocks_second_create() {
        // Given
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(task("TODO")));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID)).thenReturn(Optional.empty());
        when(commandLogRepo.findPendingCreateByLocalId(LOCAL_ID)).thenReturn(Optional.of(
            new PendingWriteCommand(UUID.randomUUID(), USER_ID, LOCAL_ID,
                CommandType.REMINDER, Operation.CREATED, null, "{}", "PENDING")));

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM"));

        // Then
        verify(commandPublisher, never()).publish(any(), anyString());
        verify(commandLogRepo, never()).upsertPending(any());
    }

    @Test
    @DisplayName("retry of the same outbox event reuses its deterministic command id and re-publishes")
    void retry_reuses_deterministic_command_id() {
        // Given the pending row left by the first (failed) attempt of this same outbox event
        OutboxEvent event = event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM");
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(task("TODO")));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID)).thenReturn(Optional.empty());

        service.propagate(event);
        ArgumentCaptor<WriteCommand> first = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(first.capture(), eq(LOCAL_ID.toString()));
        UUID commandId = first.getValue().commandId();
        when(commandLogRepo.findPendingCreateByLocalId(LOCAL_ID)).thenReturn(Optional.of(
            new PendingWriteCommand(commandId, USER_ID, LOCAL_ID,
                CommandType.REMINDER, Operation.CREATED, null, "{}", "PENDING")));

        // When the same event is drained again
        service.propagate(event);

        // Then the same command id is re-published (SQS + SentinelAPI dedup absorb it)
        ArgumentCaptor<WriteCommand> retried = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher, org.mockito.Mockito.times(2)).publish(retried.capture(), eq(LOCAL_ID.toString()));
        assertThat(retried.getValue().commandId()).isEqualTo(commandId);
    }

    @Test
    @DisplayName("DELETE: mapped executable emits DELETED using the payload type hint (CA-4)")
    void delete_emits_deleted_command() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID))
            .thenReturn(Optional.of(mapping("EK-1")));

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableDeletedEvent", "SYSTEM",
            "{\"type\":\"ACTIVITY\"}"));

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(captor.capture(), eq("EK-1"));
        assertThat(captor.getValue().operation()).isEqualTo(Operation.DELETED);
        assertThat(captor.getValue().commandType()).isEqualTo(CommandType.CALENDAR_EVENT);
        assertThat(captor.getValue().entityId()).isEqualTo("EK-1");
        assertThat(captor.getValue().payload()).isNull();
        verifyNoInteractions(executableRepo);
    }

    @Test
    @DisplayName("DELETE without a sync_mapping needs no write-back")
    void delete_without_mapping_is_skipped() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", LOCAL_ID)).thenReturn(Optional.empty());

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableDeletedEvent", "SYSTEM"));

        // Then
        verify(commandPublisher, never()).publish(any(), anyString());
        verifyNoInteractions(commandLogRepo);
    }

    @Test
    @DisplayName("missing executable row is skipped without publishing")
    void missing_executable_is_skipped() {
        // Given
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.empty());

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM"));

        // Then
        verify(commandPublisher, never()).publish(any(), anyString());
        verifyNoInteractions(commandLogRepo);
    }

    private static OutboxEvent event(String aggregateType, String eventType, String sourceSystem) {
        return event(aggregateType, eventType, sourceSystem, "{}");
    }

    private static OutboxEvent event(String aggregateType, String eventType, String sourceSystem, String payload) {
        return new OutboxEvent(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            aggregateType, LOCAL_ID.toString(), eventType, payload, sourceSystem, OffsetDateTime.now());
    }

    private static CoreExecutable task(String status) {
        return executable("TASK", status);
    }

    private static CoreExecutable executable(String type, String status) {
        return new CoreExecutable(LOCAL_ID, USER_ID, "Buy groceries", null, type, status, START, null, "HyperBrain");
    }

    private static SyncMapping mapping(String externalId) {
        return new SyncMapping(UUID.randomUUID(), USER_ID, LOCAL_ID,
            "APPLE", externalId, "checksum", "SYNCED", OffsetDateTime.now());
    }
}
