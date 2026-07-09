package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.domain.service.NotionTaskInboundMapper;
import com.hyperbrain.sync.domain.service.NotionTaskMapper;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotionTaskSyncService — inbound upsert (CA-4/5/6/7/28/29)")
class NotionTaskSyncServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CYCLE_LOCAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PAGE_ID = "2fa8bc9c5d9181bab3c9f2a27fa48cc9";
    private static final String CYCLE_PAGE_ID = "1bf8bc9c5d9181d882cfe1f4aa38f295";
    private static final OffsetDateTime EDITED_AT =
        OffsetDateTime.of(2026, 7, 7, 15, 0, 0, 0, ZoneOffset.UTC);

    @Mock private CoreExecutableRepository executableRepo;
    @Mock private SyncSnapshotRepository snapshotRepo;
    @Mock private SyncMappingRepository syncMappingRepo;
    @Mock private OutboxRepository outboxRepo;
    @Mock private NotionCycleSyncService cycleSyncService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotionTaskSyncService service;

    @BeforeEach
    void setUp() {
        service = new NotionTaskSyncService(executableRepo, snapshotRepo, syncMappingRepo,
            outboxRepo, cycleSyncService, new EndTimeInvariantRule()::apply,
            objectMapper, USER_ID);
    }

    @Test
    @DisplayName("CA-28: without a mapping the page state resolves to CREATE (upsert + mapping + outbox CREATED)")
    void unmapped_page_resolves_to_create() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID)).thenReturn(Optional.empty());
        when(cycleSyncService.resolveOrImport(CYCLE_PAGE_ID)).thenReturn(CYCLE_LOCAL_ID);

        // When
        SyncOutcome outcome = service.apply(page("Write tests", "In progress", false, EDITED_AT));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.CREATED);
        ArgumentCaptor<ExecutableSnapshot> snapshot = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(executableRepo).upsert(snapshot.capture());
        assertThat(snapshot.getValue().name()).isEqualTo("Write tests");
        assertThat(snapshot.getValue().status()).isEqualTo("IN_PROGRESS");
        assertThat(snapshot.getValue().cycleId()).isEqualTo(CYCLE_LOCAL_ID);

        ArgumentCaptor<SyncMapping> mapping = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).insert(mapping.capture());
        assertThat(mapping.getValue().externalId()).isEqualTo(PAGE_ID);
        assertThat(mapping.getValue().localId()).isEqualTo(snapshot.getValue().id());
        // last_synced_at stores the page's own last_edited_time (CA-29 compares Notion clocks)
        assertThat(mapping.getValue().lastSyncedAt()).isEqualTo(EDITED_AT);

        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        assertThat(outbox.getValue().eventType()).isEqualTo("ExecutableCreatedEvent");
        assertThat(outbox.getValue().sourceSystem()).isEqualTo("NOTION");
        assertThat(outbox.getValue().aggregateType()).isEqualTo("CORE_EXECUTABLE");
    }

    @Test
    @DisplayName("CA-28: an existing mapping resolves to UPDATE on the mapped local id, whatever the webhook type was")
    void mapped_page_resolves_to_update() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("old-checksum", EDITED_AT.minusMinutes(5))));
        when(snapshotRepo.findExecutable(LOCAL_ID)).thenReturn(Optional.of(
            ExecutableSnapshotBuilder.snapshot().id(LOCAL_ID).userId(USER_ID)
                .name("Write tests").build()));
        when(cycleSyncService.resolveOrImport(CYCLE_PAGE_ID)).thenReturn(CYCLE_LOCAL_ID);

        // When
        SyncOutcome outcome = service.apply(page("Renamed", "Done", true, EDITED_AT));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.UPDATED);
        ArgumentCaptor<ExecutableSnapshot> snapshot = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(executableRepo).upsert(snapshot.capture());
        assertThat(snapshot.getValue().id()).isEqualTo(LOCAL_ID);
        assertThat(snapshot.getValue().status()).isEqualTo("DONE");
        verify(syncMappingRepo).update(any());
        verify(syncMappingRepo, never()).insert(any());

        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        assertThat(outbox.getValue().eventType()).isEqualTo("ExecutableUpdatedEvent");
    }

    @Test
    @DisplayName("CA-29: a page state older than last_synced_at is discarded without writing (out-of-order burst)")
    void stale_page_state_is_discarded() {
        // Given the mapping already synced a newer state
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("any", EDITED_AT.plusSeconds(30))));

        // When
        SyncOutcome outcome = service.apply(page("Old name", "Not started", false, EDITED_AT));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED_STALE);
        verify(executableRepo, never()).upsert(any());
        verify(syncMappingRepo, never()).update(any());
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("CA-29: an edit in the same minute is applied — Notion truncates last_edited_time, so an equal timestamp is not stale")
    void same_minute_edit_is_applied() {
        // Given a mapping already synced at the exact same (minute-truncated) timestamp
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("previous-checksum", EDITED_AT)));
        when(snapshotRepo.findExecutable(LOCAL_ID)).thenReturn(Optional.of(
            ExecutableSnapshotBuilder.snapshot().id(LOCAL_ID).userId(USER_ID)
                .name("Write tests").build()));
        when(cycleSyncService.resolveOrImport(CYCLE_PAGE_ID)).thenReturn(CYCLE_LOCAL_ID);

        // When the user edits again within the same minute (different content, same timestamp)
        SyncOutcome outcome = service.apply(page("Renamed within the minute", "In progress", false, EDITED_AT));

        // Then the edit is applied instead of being discarded as stale
        assertThat(outcome).isEqualTo(SyncOutcome.UPDATED);
        ArgumentCaptor<ExecutableSnapshot> snapshot = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(executableRepo).upsert(snapshot.capture());
        assertThat(snapshot.getValue().name()).isEqualTo("Renamed within the minute");
    }

    @Test
    @DisplayName("CA-4/CA-20: identical state (checksum match) is discarded silently — the HU-10 echo does not bounce")
    void identical_state_is_discarded_by_checksum() {
        // Given a mapping whose checksum equals the canonical props of the incoming state
        NotionTaskPage page = page("Write tests", "In progress", false, EDITED_AT);
        ExecutableSnapshot snapshot = NotionTaskInboundMapper.toSnapshot(
            page, LOCAL_ID, USER_ID, CYCLE_LOCAL_ID, null);
        Map<String, Object> canonicalProps = NotionTaskMapper.map(snapshot, CYCLE_PAGE_ID, null);
        String storedChecksum = ChecksumSupport.compute(PAGE_ID, canonicalProps, objectMapper);
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping(storedChecksum, EDITED_AT.minusMinutes(5))));
        when(snapshotRepo.findExecutable(LOCAL_ID)).thenReturn(Optional.of(snapshot));
        when(cycleSyncService.resolveOrImport(CYCLE_PAGE_ID)).thenReturn(CYCLE_LOCAL_ID);

        // When
        SyncOutcome outcome = service.apply(page);

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED_ECHO);
        verify(executableRepo, never()).upsert(any());
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("CA-7: an archived page deletes the executable and its mapping and emits DELETED with the type hint")
    void archived_page_resolves_to_delete() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("any", EDITED_AT)));
        when(executableRepo.findById(LOCAL_ID)).thenReturn(Optional.of(new CoreExecutable(
            LOCAL_ID, USER_ID, "Write tests", null, "ACTIVITY", "TODO", null, null, null, false)));

        // When
        SyncOutcome outcome = service.apply(new NotionTaskPage(PAGE_ID, EDITED_AT, true,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.DELETED);
        verify(executableRepo).deleteById(LOCAL_ID);
        verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", PAGE_ID);
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        assertThat(outbox.getValue().eventType()).isEqualTo("ExecutableDeletedEvent");
        // The Apple propagator derives CALENDAR_EVENT from this hint after the row is gone
        assertThat(outbox.getValue().payload()).contains("\"type\":\"ACTIVITY\"");
    }

    @Test
    @DisplayName("an archived page without mapping is a no-op DELETE")
    void archived_unmapped_page_is_noop() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID)).thenReturn(Optional.empty());

        // When
        SyncOutcome outcome = service.apply(new NotionTaskPage(PAGE_ID, EDITED_AT, true,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.DELETED);
        verify(executableRepo, never()).deleteById(any());
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("CA-6: an unmapped parent leaves the relation null with a warning; the task still persists")
    void unmapped_parent_is_omitted() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID)).thenReturn(Optional.empty());
        when(cycleSyncService.resolveOrImport(null)).thenReturn(null);
        String parentPageId = "aaaa0000000000000000000000000001";
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", parentPageId))
            .thenReturn(Optional.empty());

        // When
        NotionTaskPage page = new NotionTaskPage(PAGE_ID, EDITED_AT, false,
            "Child task", null, null, false, null, null, null, null, null, null,
            null, null, null, null, null, null, parentPageId);
        SyncOutcome outcome = service.apply(page);

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.CREATED);
        ArgumentCaptor<ExecutableSnapshot> snapshot = ArgumentCaptor.forClass(ExecutableSnapshot.class);
        verify(executableRepo).upsert(snapshot.capture());
        assertThat(snapshot.getValue().parentId()).isNull();
    }

    private static NotionTaskPage page(String name, String status, Boolean complete,
                                       OffsetDateTime editedAt) {
        return new NotionTaskPage(PAGE_ID, editedAt, false,
            name, "Detailed description", status, complete, "Task",
            null, null, 0.8, 0.6, 2.5, null, null, "Alto", "Intenso", "Rutinario",
            CYCLE_PAGE_ID, null);
    }

    private static SyncMapping mapping(String checksum, OffsetDateTime lastSyncedAt) {
        return new SyncMapping(UUID.randomUUID(), USER_ID, LOCAL_ID,
            "NOTION", PAGE_ID, checksum, "SYNCED", lastSyncedAt);
    }
}
