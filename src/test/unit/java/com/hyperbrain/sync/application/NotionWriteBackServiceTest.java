package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.infrastructure.NotionSyncProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("NotionWriteBackService")
class NotionWriteBackServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CYCLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TASKS_DS = "tasksds";
    private static final String CYCLES_DS = "cyclesds";
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 7, 6, 14, 0, 0, 0, ZoneOffset.UTC);

    private SyncSnapshotRepository snapshotRepo;
    private SyncMappingRepository syncMappingRepo;
    private NotionPort notion;
    private SimpleMeterRegistry meterRegistry;
    private NotionWriteBackService service;

    @BeforeEach
    void setUp() {
        snapshotRepo = mock(SyncSnapshotRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        notion = mock(NotionPort.class);
        meterRegistry = new SimpleMeterRegistry();
        NotionSyncProperties properties = new NotionSyncProperties();
        properties.setEnabled(true);
        properties.setTasksDataSourceId(TASKS_DS);
        properties.setCyclesDataSourceId(CYCLES_DS);
        service = new NotionWriteBackService(snapshotRepo, syncMappingRepo, notion,
            properties, new ObjectMapper(), meterRegistry);
    }

    // ── Routing (CA-2, RF-17) ─────────────────────────────────────────────────

    @Test
    @DisplayName("loop protection: source_system=NOTION never bounces back to Notion (CA-2)")
    void notion_sourced_event_is_not_propagated() {
        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "NOTION"));

        // Then
        verifyNoInteractions(snapshotRepo, syncMappingRepo, notion);
    }

    @Test
    @DisplayName("loop protection: events with unknown origin are not propagated")
    void null_source_event_is_not_propagated() {
        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", null));

        // Then
        verifyNoInteractions(snapshotRepo, syncMappingRepo, notion);
    }

    @Test
    @DisplayName("source_system=APPLE and SYSTEM do propagate (CA-2)")
    void apple_and_system_sources_propagate() {
        // Given
        givenUnmappedExecutable(taskSnapshot("TODO"));
        when(notion.createPage(eq(TASKS_DS), anyMap())).thenReturn("page-1", "page-2");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "APPLE"));
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM"));

        // Then
        verify(notion, org.mockito.Mockito.times(2)).createPage(eq(TASKS_DS), anyMap());
    }

    @Test
    @DisplayName("events of other aggregates or unknown event types are ignored")
    void unrelated_events_are_ignored() {
        // When
        service.propagate(event("FIN_TRANSACTION", "TransactionLoggedEvent", "SYSTEM"));
        service.propagate(event("CORE_EXECUTABLE", "SomethingElseEvent", "SYSTEM"));

        // Then
        verifyNoInteractions(snapshotRepo, syncMappingRepo, notion);
    }

    // ── Inbound Apple sync events (SYNC_APPLE aggregate, HU-09 handlers) ─────

    @Test
    @DisplayName("SYNC_APPLE ReminderSyncedEvent propagates using the payload local_id (CA-2)")
    void apple_sync_event_propagates_via_payload_local_id() {
        // Given an inbound reminder ingested by HU-09 (aggregate_id = EventKit id)
        givenUnmappedExecutable(taskSnapshot("TODO"));
        when(notion.createPage(eq(TASKS_DS), anyMap())).thenReturn("applepage");
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SYNC_APPLE",
            "EKReminder-ABC123",
            "ReminderSyncedEvent",
            "{\"local_id\":\"" + LOCAL_ID + "\",\"entity_id\":\"EKReminder-ABC123\",\"operation\":\"CREATED\"}",
            "APPLE", OffsetDateTime.now());

        // When
        service.propagate(event);

        // Then the page is created for the executable referenced by payload.local_id
        verify(notion).createPage(eq(TASKS_DS), anyMap());
        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).insert(captor.capture());
        assertThat(captor.getValue().localId()).isEqualTo(LOCAL_ID);
    }

    @Test
    @DisplayName("SYNC_APPLE ReminderDeletedEvent archives the mapped page")
    void apple_sync_delete_event_archives_page() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.of(mapping(LOCAL_ID, "applepage", null)));
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "SYNC_APPLE",
            "EKReminder-ABC123",
            "ReminderDeletedEvent",
            "{\"local_id\":\"" + LOCAL_ID + "\",\"entity_id\":\"EKReminder-ABC123\",\"operation\":\"DELETED\"}",
            "APPLE", OffsetDateTime.now());

        // When
        service.propagate(event);

        // Then
        verify(notion).archivePage("applepage");
        verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", "applepage");
    }

    @Test
    @DisplayName("SYNC_APPLE event without a usable local_id is skipped")
    void apple_sync_event_without_local_id_is_skipped() {
        // When
        service.propagate(new OutboxEvent(UUID.randomUUID(), "SYNC_APPLE", "EKReminder-X",
            "ReminderSyncedEvent", "{\"entity_id\":\"EKReminder-X\"}", "APPLE", OffsetDateTime.now()));
        service.propagate(new OutboxEvent(UUID.randomUUID(), "SYNC_APPLE", "EKReminder-X",
            "ReminderSyncedEvent", "not-json", "APPLE", OffsetDateTime.now()));

        // Then
        verifyNoInteractions(notion, snapshotRepo);
    }

    // ── CREATE (CA-3) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CREATE: unmapped executable creates the page and persists the normalized page_id (CA-3)")
    void create_persists_mapping_with_normalized_page_id() {
        // Given
        givenUnmappedExecutable(taskSnapshot("TODO"));
        when(notion.createPage(eq(TASKS_DS), anyMap()))
            .thenReturn("2fa8bc9c-5d91-81ba-b3c9-f2a27fa48cc9");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM"));

        // Then
        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).insert(captor.capture());
        SyncMapping mapping = captor.getValue();
        assertThat(mapping.externalId()).isEqualTo("2fa8bc9c5d9181bab3c9f2a27fa48cc9");
        assertThat(mapping.externalSystem()).isEqualTo("NOTION");
        assertThat(mapping.localId()).isEqualTo(LOCAL_ID);
        assertThat(mapping.userId()).isEqualTo(USER_ID);
        assertThat(mapping.lastKnownChecksum()).hasSize(64);
        assertThat(mapping.syncStatus()).isEqualTo("SYNCED");
        assertThat(mapping.lastSyncedAt()).isNotNull();
        assertThat(meterRegistry.counter("hyperbrain.sync.notion.writes",
            "operation", "created").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CREATE: missing executable row is skipped without calling Notion")
    void create_skips_missing_executable() {
        // Given
        when(snapshotRepo.findExecutable(LOCAL_ID)).thenReturn(Optional.empty());

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM"));

        // Then
        verifyNoInteractions(notion, syncMappingRepo);
    }

    // ── UPDATE (CA-4) + closure ───────────────────────────────────────────────

    @Test
    @DisplayName("UPDATE: mapped executable patches the page and refreshes checksum + last_synced_at (CA-4, CA-7)")
    void update_patches_page_and_refreshes_mapping() {
        // Given
        SyncMapping existing = mapping(LOCAL_ID, "page123", "old-checksum");
        givenExecutable(taskSnapshot("IN_PROGRESS"), existing);

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "APPLE"));

        // Then
        verify(notion).updatePage(eq("page123"), anyMap());
        verify(notion, never()).createPage(anyString(), anyMap());
        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).update(captor.capture());
        SyncMapping updated = captor.getValue();
        assertThat(updated.externalId()).isEqualTo("page123");
        assertThat(updated.lastKnownChecksum()).hasSize(64).isNotEqualTo("old-checksum");
        assertThat(updated.syncStatus()).isEqualTo("SYNCED");
        assertThat(updated.lastSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("closure: TaskCompletedEvent writes Status=Done and Complete=true")
    void completion_writes_done_and_complete() {
        // Given
        givenExecutable(taskSnapshot("DONE"), mapping(LOCAL_ID, "page123", null));

        // When
        service.propagate(event("CORE_EXECUTABLE", "TaskCompletedEvent", "APPLE"));

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(notion).updatePage(eq("page123"), props.capture());
        assertThat(props.getValue().get("Status"))
            .isEqualTo(Map.of("status", Map.of("name", "Done")));
        assertThat(props.getValue().get("Complete")).isEqualTo(Map.of("checkbox", true));
    }

    @Test
    @DisplayName("UPDATE without mapping is treated as a new entity and creates the page")
    void update_without_mapping_creates_page() {
        // Given
        givenUnmappedExecutable(taskSnapshot("TODO"));
        when(notion.createPage(eq(TASKS_DS), anyMap())).thenReturn("newpage");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM"));

        // Then
        verify(notion).createPage(eq(TASKS_DS), anyMap());
        verify(syncMappingRepo).insert(any(SyncMapping.class));
    }

    // ── DELETE (CA-5) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE: archives the page and removes the sync_mapping (CA-5)")
    void delete_archives_and_removes_mapping() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.of(mapping(LOCAL_ID, "page123", null)));

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableDeletedEvent", "SYSTEM"));

        // Then
        InOrder order = inOrder(notion, syncMappingRepo);
        order.verify(notion).archivePage("page123");
        order.verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", "page123");
    }

    @Test
    @DisplayName("DELETE of an unmapped entity needs no write-back")
    void delete_unmapped_is_noop() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.empty());

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableDeletedEvent", "SYSTEM"));

        // Then
        verifyNoInteractions(notion);
    }

    @Test
    @DisplayName("DELETE tolerates a page already gone in Notion and still cleans the mapping (CA-15)")
    void delete_tolerates_missing_page() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.of(mapping(LOCAL_ID, "page123", null)));
        doThrow(new NotionPageNotFoundException("page123")).when(notion).archivePage("page123");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableDeletedEvent", "SYSTEM"));

        // Then
        verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", "page123");
    }

    // ── Cycles (CA-6, ADR-011) ────────────────────────────────────────────────

    @Test
    @DisplayName("CORE_CYCLE events propagate to the Cycles data source (CA-6)")
    void cycle_event_creates_cycle_page() {
        // Given
        when(snapshotRepo.findCycle(LOCAL_ID)).thenReturn(Optional.of(cycleSnapshot(LOCAL_ID)));
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.empty());
        when(notion.createPage(eq(CYCLES_DS), anyMap())).thenReturn("cyclepage");

        // When
        service.propagate(event("CORE_CYCLE", "CycleCreatedEvent", "SYSTEM"));

        // Then
        verify(notion).createPage(eq(CYCLES_DS), anyMap());
        verify(syncMappingRepo).insert(any(SyncMapping.class));
    }

    @Test
    @DisplayName("task with an unmapped cycle creates the cycle page first and links the relation (CA-6)")
    void unmapped_cycle_is_created_before_the_task() {
        // Given
        givenUnmappedExecutable(taskSnapshotWithCycle());
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", CYCLE_ID))
            .thenReturn(Optional.empty());
        when(snapshotRepo.findCycle(CYCLE_ID)).thenReturn(Optional.of(cycleSnapshot(CYCLE_ID)));
        when(notion.createPage(eq(CYCLES_DS), anyMap())).thenReturn("cyclepage");
        when(notion.createPage(eq(TASKS_DS), anyMap())).thenReturn("taskpage");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM"));

        // Then the cycle page is created before the task page and the relation resolves
        InOrder order = inOrder(notion);
        order.verify(notion).createPage(eq(CYCLES_DS), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> taskProps = ArgumentCaptor.forClass(Map.class);
        order.verify(notion).createPage(eq(TASKS_DS), taskProps.capture());
        assertThat(taskProps.getValue().get("Cycle"))
            .isEqualTo(Map.of("relation", List.of(Map.of("id", "cyclepage"))));
        verify(syncMappingRepo, org.mockito.Mockito.times(2)).insert(any(SyncMapping.class));
    }

    @Test
    @DisplayName("task with a mapped cycle reuses the existing relation id")
    void mapped_cycle_is_reused() {
        // Given
        givenUnmappedExecutable(taskSnapshotWithCycle());
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", CYCLE_ID))
            .thenReturn(Optional.of(mapping(CYCLE_ID, "existingcycle", null)));
        when(notion.createPage(eq(TASKS_DS), anyMap())).thenReturn("taskpage");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableCreatedEvent", "SYSTEM"));

        // Then
        verify(notion, never()).createPage(eq(CYCLES_DS), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(notion).createPage(eq(TASKS_DS), props.capture());
        assertThat(props.getValue().get("Cycle"))
            .isEqualTo(Map.of("relation", List.of(Map.of("id", "existingcycle"))));
    }

    // ── Negative cases (CA-13, CA-15) ─────────────────────────────────────────

    @Test
    @DisplayName("404 on UPDATE repairs the mapping: drops it and recreates the page (CA-15)")
    void not_found_on_update_recreates_page() {
        // Given
        givenExecutable(taskSnapshot("TODO"), mapping(LOCAL_ID, "stalepage", null));
        doThrow(new NotionPageNotFoundException("stalepage"))
            .when(notion).updatePage(eq("stalepage"), anyMap());
        when(notion.createPage(eq(TASKS_DS), anyMap())).thenReturn("freshpage");

        // When
        service.propagate(event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM"));

        // Then
        InOrder order = inOrder(syncMappingRepo, notion);
        order.verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", "stalepage");
        order.verify(notion).createPage(eq(TASKS_DS), anyMap());
        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).insert(captor.capture());
        assertThat(captor.getValue().externalId()).isEqualTo("freshpage");
    }

    @Test
    @DisplayName("persistent API failure marks the mapping ERROR, counts the failure and rethrows (CA-13)")
    void persistent_failure_marks_error_and_rethrows() {
        // Given
        SyncMapping existing = mapping(LOCAL_ID, "page123", "checksum");
        givenExecutable(taskSnapshot("TODO"), existing);
        doThrow(new NotionApiException("Notion down")).when(notion)
            .updatePage(eq("page123"), anyMap());

        // When / Then
        assertThatExceptionOfType(NotionApiException.class)
            .isThrownBy(() -> service.propagate(
                event("CORE_EXECUTABLE", "ExecutableUpdatedEvent", "SYSTEM")));

        ArgumentCaptor<SyncMapping> captor = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).update(captor.capture());
        assertThat(captor.getValue().syncStatus()).isEqualTo("ERROR");
        assertThat(captor.getValue().lastKnownChecksum()).isEqualTo("checksum");
        assertThat(meterRegistry.counter("hyperbrain.sync.notion.failures").count())
            .isEqualTo(1.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void givenUnmappedExecutable(ExecutableSnapshot snapshot) {
        when(snapshotRepo.findExecutable(LOCAL_ID)).thenReturn(Optional.of(snapshot));
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.empty());
    }

    private void givenExecutable(ExecutableSnapshot snapshot, SyncMapping mapping) {
        when(snapshotRepo.findExecutable(LOCAL_ID)).thenReturn(Optional.of(snapshot));
        when(syncMappingRepo.findByExternalSystemAndLocalId("NOTION", LOCAL_ID))
            .thenReturn(Optional.of(mapping));
    }

    private static ExecutableSnapshot taskSnapshot(String status) {
        return new ExecutableSnapshot(LOCAL_ID, USER_ID, null, null, "Write tests", null,
            "TASK", status, null, null, null, START, null, null, null, null);
    }

    private static ExecutableSnapshot taskSnapshotWithCycle() {
        return new ExecutableSnapshot(LOCAL_ID, USER_ID, null, CYCLE_ID, "Write tests", null,
            "TASK", "TODO", null, null, null, null, null, null, null, null);
    }

    private static CycleSnapshot cycleSnapshot(UUID id) {
        return new CycleSnapshot(id, USER_ID, "Sprint 2", "MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14));
    }

    private static SyncMapping mapping(UUID localId, String externalId, String checksum) {
        return new SyncMapping(UUID.randomUUID(), USER_ID, localId, "NOTION", externalId,
            checksum, "SYNCED", OffsetDateTime.now());
    }

    private static OutboxEvent event(String aggregateType, String eventType, String sourceSystem) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, LOCAL_ID.toString(),
            eventType, "{}", sourceSystem, OffsetDateTime.now());
    }
}
