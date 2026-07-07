package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.CoreCycleRepository;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotionCycleSyncService — inbound upsert + relation import (CA-5/28/29)")
class NotionCycleSyncServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PAGE_ID = "1bf8bc9c5d9181d882cfe1f4aa38f295";
    private static final OffsetDateTime EDITED_AT =
        OffsetDateTime.of(2026, 7, 7, 15, 0, 0, 0, ZoneOffset.UTC);

    @Mock private CoreCycleRepository cycleRepo;
    @Mock private SyncMappingRepository syncMappingRepo;
    @Mock private OutboxRepository outboxRepo;
    @Mock private NotionPort notion;

    private NotionCycleSyncService service;

    @BeforeEach
    void setUp() {
        service = new NotionCycleSyncService(cycleRepo, syncMappingRepo, outboxRepo, notion,
            new NotionPageParser(), new ObjectMapper(), USER_ID);
    }

    @Test
    @DisplayName("CA-28: without a mapping the page state resolves to CREATE")
    void unmapped_page_resolves_to_create() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID)).thenReturn(Optional.empty());

        // When
        SyncOutcome outcome = service.apply(page("Sprint 2", false, EDITED_AT));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.CREATED);
        ArgumentCaptor<CycleSnapshot> snapshot = ArgumentCaptor.forClass(CycleSnapshot.class);
        verify(cycleRepo).upsert(snapshot.capture());
        assertThat(snapshot.getValue().name()).isEqualTo("Sprint 2");
        assertThat(snapshot.getValue().status()).isEqualTo("ACTIVE");
        verify(syncMappingRepo).insert(any());
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        assertThat(outbox.getValue().aggregateType()).isEqualTo("CORE_CYCLE");
        assertThat(outbox.getValue().eventType()).isEqualTo("CycleCreatedEvent");
        assertThat(outbox.getValue().sourceSystem()).isEqualTo("NOTION");
    }

    @Test
    @DisplayName("CA-29: a stale page state is discarded without writing")
    void stale_page_state_is_discarded() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("any", EDITED_AT.plusSeconds(10))));

        // When
        SyncOutcome outcome = service.apply(page("Sprint 2", false, EDITED_AT));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED_STALE);
        verify(cycleRepo, never()).upsert(any());
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("CA-29: an edit in the same minute is applied — Notion truncates last_edited_time, so an equal timestamp is not stale")
    void same_minute_edit_is_applied() {
        // Given a mapping already synced at the exact same (minute-truncated) timestamp
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("previous-checksum", EDITED_AT)));

        // When the user edits again within the same minute (different content, same timestamp)
        SyncOutcome outcome = service.apply(page("Sprint 2 renamed", false, EDITED_AT));

        // Then the edit is applied instead of being discarded as stale
        assertThat(outcome).isEqualTo(SyncOutcome.UPDATED);
        ArgumentCaptor<CycleSnapshot> snapshot = ArgumentCaptor.forClass(CycleSnapshot.class);
        verify(cycleRepo).upsert(snapshot.capture());
        assertThat(snapshot.getValue().name()).isEqualTo("Sprint 2 renamed");
    }

    @Test
    @DisplayName("CA-7: an archived Cycles page deletes the cycle and its mapping")
    void archived_page_resolves_to_delete() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("any", EDITED_AT)));

        // When
        SyncOutcome outcome = service.apply(new NotionCyclePage(PAGE_ID, EDITED_AT, true,
            null, null, null, null, null));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.DELETED);
        verify(cycleRepo).deleteById(LOCAL_ID);
        verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", PAGE_ID);
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(outbox.capture());
        assertThat(outbox.getValue().eventType()).isEqualTo("CycleDeletedEvent");
    }

    @Test
    @DisplayName("CA-5: resolveOrImport returns the mapped local id without calling the API")
    void resolve_mapped_cycle_uses_mapping() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(mapping("any", EDITED_AT)));

        // When / Then
        assertThat(service.resolveOrImport(PAGE_ID)).isEqualTo(LOCAL_ID);
        verify(notion, never()).retrievePage(any());
    }

    @Test
    @DisplayName("CA-5: an unmapped cycle is imported from the Cycles database before resolving")
    void resolve_unmapped_cycle_imports_it_first() {
        // Given no mapping on the first lookup, then the one created by the import
        SyncMapping created = mapping("fresh", EDITED_AT);
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(created));
        when(notion.retrievePage(PAGE_ID)).thenReturn("""
            {"object":"page","id":"%s","last_edited_time":"2026-07-07T15:00:00Z","properties":{
              "Name":{"title":[{"plain_text":"Sprint 2"}]},
              "Type":{"select":{"name":"MCI"}},
              "Inactive":{"checkbox":false}}}
            """.formatted(PAGE_ID));

        // When
        UUID resolved = service.resolveOrImport(PAGE_ID);

        // Then the cycle was imported through the regular upsert path
        assertThat(resolved).isEqualTo(LOCAL_ID);
        verify(cycleRepo).upsert(any());
        verify(syncMappingRepo).insert(any());
    }

    @Test
    @DisplayName("resolveOrImport omits the relation when the cycle page vanished (404)")
    void resolve_vanished_cycle_returns_null() {
        // Given
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID)).thenReturn(Optional.empty());
        when(notion.retrievePage(PAGE_ID)).thenThrow(new NotionPageNotFoundException(PAGE_ID));

        // When / Then
        assertThat(service.resolveOrImport(PAGE_ID)).isNull();
        verify(cycleRepo, never()).upsert(any());
    }

    private static NotionCyclePage page(String name, Boolean inactive, OffsetDateTime editedAt) {
        return new NotionCyclePage(PAGE_ID, editedAt, false, name, "MCI",
            "2026-07-01", "2026-07-14", inactive);
    }

    private static SyncMapping mapping(String checksum, OffsetDateTime lastSyncedAt) {
        return new SyncMapping(UUID.randomUUID(), USER_ID, LOCAL_ID,
            "NOTION", PAGE_ID, checksum, "SYNCED", lastSyncedAt);
    }
}
