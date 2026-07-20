package com.hyperbrain.sync.application;

import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.ResultStatus;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.WriteCommandResult;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
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

@DisplayName("WriteCommandResultService")
class WriteCommandResultServiceTest {

    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PAYLOAD_JSON = "{\"title\":\"Buy groceries\"}";

    private ProcessedMessageStore processedMessageStore;
    private WriteCommandLogRepository commandLogRepo;
    private SyncMappingRepository syncMappingRepo;
    private CoreExecutableRepository executableRepo;
    private WriteCommandResultService service;

    @BeforeEach
    void setUp() {
        processedMessageStore = mock(ProcessedMessageStore.class);
        commandLogRepo = mock(WriteCommandLogRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        executableRepo = mock(CoreExecutableRepository.class);
        service = new WriteCommandResultService(processedMessageStore, commandLogRepo, syncMappingRepo, executableRepo);
        when(processedMessageStore.markProcessed(anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("APPLIED CREATED closes the sync_mapping with the echoed EventKit id (CA-3)")
    void applied_created_closes_mapping() {
        // Given
        when(commandLogRepo.findById(COMMAND_ID))
            .thenReturn(Optional.of(pending(Operation.CREATED, null)));
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EK-NEW")).thenReturn(Optional.empty());

        // When
        service.handle(result(ResultStatus.APPLIED, Operation.CREATED, "EK-NEW", null));

        // Then
        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).insert(captor.capture());
        SyncMapping mapping = captor.getValue();
        assertThat(mapping.userId()).isEqualTo(USER_ID);
        assertThat(mapping.localId()).isEqualTo(LOCAL_ID);
        assertThat(mapping.externalSystem()).isEqualTo("APPLE");
        assertThat(mapping.externalId()).isEqualTo("EK-NEW");
        assertThat(mapping.syncStatus()).isEqualTo("SYNCED");
        assertThat(mapping.lastKnownChecksum())
            .isEqualTo(ChecksumCalculator.compute("EK-NEW", "CREATED", PAYLOAD_JSON));
        verify(commandLogRepo).markApplied(eq(COMMAND_ID), eq("EK-NEW"), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("APPLIED CREATED when the echo already created the mapping refreshes it instead")
    void applied_created_with_existing_mapping_updates() {
        // Given
        when(commandLogRepo.findById(COMMAND_ID))
            .thenReturn(Optional.of(pending(Operation.CREATED, null)));
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EK-NEW"))
            .thenReturn(Optional.of(mapping("EK-NEW")));

        // When
        service.handle(result(ResultStatus.APPLIED, Operation.CREATED, "EK-NEW", null));

        // Then
        verify(syncMappingRepo).update(any(SyncMapping.class));
        verify(syncMappingRepo, never()).insert(any());
    }

    @Test
    @DisplayName("APPLIED UPDATED refreshes checksum and last_synced_at (CA-7)")
    void applied_updated_refreshes_checksum() {
        // Given
        when(commandLogRepo.findById(COMMAND_ID))
            .thenReturn(Optional.of(pending(Operation.UPDATED, "EK-1")));
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EK-1"))
            .thenReturn(Optional.of(mapping("EK-1")));

        // When
        service.handle(result(ResultStatus.APPLIED, Operation.UPDATED, "EK-1", null));

        // Then
        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).update(captor.capture());
        assertThat(captor.getValue().lastKnownChecksum())
            .isEqualTo(ChecksumCalculator.compute("EK-1", "UPDATED", PAYLOAD_JSON));
        assertThat(captor.getValue().lastSyncedAt()).isNotNull();
        verify(commandLogRepo).markApplied(eq(COMMAND_ID), eq("EK-1"), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("APPLIED DELETED removes the sync_mapping (CA-4)")
    void applied_deleted_removes_mapping() {
        // Given
        when(commandLogRepo.findById(COMMAND_ID))
            .thenReturn(Optional.of(pending(Operation.DELETED, "EK-1")));

        // When
        service.handle(result(ResultStatus.APPLIED, Operation.DELETED, "EK-1", null));

        // Then
        verify(syncMappingRepo).deleteByExternalSystemAndId("APPLE", "EK-1");
        verify(commandLogRepo).markApplied(eq(COMMAND_ID), eq("EK-1"), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("FAILED marks the command row with the error and leaves the mapping untouched (CA-20)")
    void failed_marks_command_row() {
        // Given
        when(commandLogRepo.findById(COMMAND_ID))
            .thenReturn(Optional.of(pending(Operation.CREATED, null)));

        // When
        service.handle(result(ResultStatus.FAILED, Operation.CREATED, null, "EventKit denied"));

        // Then
        verify(commandLogRepo).markFailed(eq(COMMAND_ID), eq("EventKit denied"), any(OffsetDateTime.class));
        verifyNoInteractions(syncMappingRepo);
    }

    @Test
    @DisplayName("duplicate result is ignored (idempotency, CA-18)")
    void duplicate_result_is_ignored() {
        // Given
        when(processedMessageStore.markProcessed(anyString(), anyString())).thenReturn(false);

        // When
        service.handle(result(ResultStatus.APPLIED, Operation.CREATED, "EK-NEW", null));

        // Then
        verifyNoInteractions(commandLogRepo, syncMappingRepo);
    }

    @Test
    @DisplayName("result without a matching command row is ignored")
    void unknown_command_is_ignored() {
        // Given
        when(commandLogRepo.findById(COMMAND_ID)).thenReturn(Optional.empty());

        // When
        service.handle(result(ResultStatus.APPLIED, Operation.CREATED, "EK-NEW", null));

        // Then
        verifyNoInteractions(syncMappingRepo);
        verify(commandLogRepo, never()).markApplied(any(), anyString(), any());
    }

    private static SyncMapping mapping(String externalId) {
        return new SyncMapping(UUID.randomUUID(), USER_ID, LOCAL_ID,
            "APPLE", externalId, "old-checksum", "SYNCED", OffsetDateTime.now());
    }

    private static PendingWriteCommand pending(Operation operation, String entityId) {
        return new PendingWriteCommand(COMMAND_ID, USER_ID, LOCAL_ID,
            CommandType.REMINDER, operation, entityId,
            operation == Operation.DELETED ? null : PAYLOAD_JSON, "PENDING");
    }

    private static WriteCommandResult result(
        ResultStatus status, Operation operation, String entityId, String error) {
        return new WriteCommandResult(COMMAND_ID, status, operation, entityId, error, OffsetDateTime.now());
    }
}
