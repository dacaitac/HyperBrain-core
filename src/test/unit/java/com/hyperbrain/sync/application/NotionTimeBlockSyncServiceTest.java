package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.NotionTimeBlockPage;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.TimeBlockEditOutcome;
import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authority-matrix guards of the Time Blocks inbound (ADR-038): the anti-echo no-op (an echo
 * never flips the origin), the {@code has_more} truncation guard, the terminal read-only wall
 * and the relation-only creation. The end-to-end apply paths (D5 move, walls, WIG) live in
 * {@code NotionTimeBlockInboundIT}.
 */
@DisplayName("NotionTimeBlockSyncService — inbound authority matrix guards (ADR-038)")
class NotionTimeBlockSyncServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLOCK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEMBER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String PAGE_ID = "b10c0000000000000000000000000001";
    private static final String TASK_PAGE_ID = "aaaa0000000000000000000000000001";

    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime EDITED_AT =
        OffsetDateTime.of(2026, 8, 5, 15, 0, 0, 0, ZoneOffset.UTC);

    private PlannerTimeBlockPort blockPort;
    private SyncMappingRepository syncMappingRepo;
    private NotionTimeBlockMirrorService mirrorService;
    private OutboxRepository outboxRepo;
    private ObjectMapper objectMapper;
    private NotionTimeBlockSyncService service;

    @BeforeEach
    void setUp() {
        blockPort = mock(PlannerTimeBlockPort.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        mirrorService = mock(NotionTimeBlockMirrorService.class);
        outboxRepo = mock(OutboxRepository.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new NotionTimeBlockSyncService(blockPort, syncMappingRepo, mirrorService,
            outboxRepo, objectMapper, USER_ID);
    }

    @Test
    @DisplayName("anti-echo guard: a checksum-matching delivery is an absolute no-op — origin never flips")
    void echo_is_absolute_noop() {
        // Given the stored checksum reproduces the canonical projection of the current row
        TimeBlockSnapshot block = plannedBlock("PLANNER");
        Map<String, Object> canonical = Map.of("Name", "canonical");
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));
        when(mirrorService.canonicalProps(block, null)).thenReturn(canonical);
        givenMapping(ChecksumSupport.compute(PAGE_ID, canonical, objectMapper));

        // When
        SyncOutcome outcome = service.apply(page(START, START.plusMinutes(90), null, false));

        // Then — no edit, no origin flip, no outbox traffic
        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED_ECHO);
        verify(blockPort, never()).applyUserEdit(any());
        verify(outboxRepo, never()).append(any());
    }

    @Test
    @DisplayName("regression core#57 ghost state: a retime applies even when the stored checksum matches the current row")
    void retime_applies_even_when_checksum_matches_current_row() {
        // Given the mapping stores the checksum of the mirror's own last write — which is the
        // canonical projection of the CURRENT row, so a checksum-first echo guard would swallow
        // the user's genuine retime (the prod ghost state: Notion moved, PG frozen, no trace)
        TimeBlockSnapshot block = plannedBlock("USER");
        Map<String, Object> canonical = Map.of("Name", "canonical");
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));
        givenMapping(ChecksumSupport.compute(PAGE_ID, canonical, objectMapper));
        when(blockPort.applyUserEdit(any())).thenReturn(new TimeBlockEditOutcome(
            true, List.of(), Set.of(), Set.of(), Set.of(), Set.of()));

        // When the user retimes the page (Notion's millisecond date format)
        SyncOutcome outcome = service.apply(new NotionTimeBlockPage(PAGE_ID, EDITED_AT, false,
            "Mié 05 · 09:00–10:30 · Theme",
            "2026-08-06T10:30:00.000-05:00", "2026-08-06T11:30:00.000-05:00",
            "Planned", "User", 90.0, null, List.of(TASK_PAGE_ID), false, null));

        // Then — the retime is applied with the exact parsed bounds, never echo-discarded
        assertThat(outcome).isEqualTo(SyncOutcome.UPDATED);
        ArgumentCaptor<com.hyperbrain.sync.domain.model.TimeBlockUserEdit> edit =
            ArgumentCaptor.forClass(com.hyperbrain.sync.domain.model.TimeBlockUserEdit.class);
        verify(blockPort).applyUserEdit(edit.capture());
        assertThat(edit.getValue().newStart())
            .isEqualTo(OffsetDateTime.of(2026, 8, 6, 10, 30, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(edit.getValue().newEnd())
            .isEqualTo(OffsetDateTime.of(2026, 8, 6, 11, 30, 0, 0, ZoneOffset.ofHours(-5)));
        // And the accepted edit stages the Apple follow-up + the canonical re-assertion
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, atLeastOnce()).append(events.capture());
        assertThat(events.getAllValues())
            .anySatisfy(event -> assertThat(event.sourceSystem()).isEqualTo("NOTION"))
            .anySatisfy(event -> assertThat(event.payload()).contains("CANONICAL_STATE"));
    }

    @Test
    @DisplayName("has_more guard: a truncated Tasks relation is never applied — canonical state re-asserted")
    void has_more_discards_membership_and_reasserts() {
        // Given a truncated relation (window/theme unchanged, so membership is the only signal)
        TimeBlockSnapshot block = plannedBlock("PLANNER");
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));
        when(mirrorService.canonicalProps(eq(block), isNull())).thenReturn(Map.of("k", "v"));
        givenMapping("some-other-checksum");

        // When
        SyncOutcome outcome = service.apply(page(START, START.plusMinutes(90), null, true));

        // Then — nothing applied; the mirror re-asserts the canonical membership
        assertThat(outcome).isEqualTo(SyncOutcome.REASSERTED);
        verify(blockPort, never()).applyUserEdit(any());
        OutboxEvent staged = capturedEvent();
        assertThat(staged.eventType()).isEqualTo("TimeBlockChangedEvent");
        assertThat(staged.sourceSystem()).isEqualTo("SYSTEM");
        assertThat(staged.payload()).contains("CANONICAL_STATE");
    }

    @Test
    @DisplayName("terminal blocks are frozen history: any edit rejects and re-asserts with a visible Sync Note")
    void terminal_edit_rejects_and_reasserts() {
        // Given a SETTLED block whose page was retimed by the user
        TimeBlockSnapshot block = new TimeBlockSnapshot(BLOCK_ID, USER_ID, START,
            START.plusMinutes(90), "SETTLED", "PLANNER", "Theme", null, 90, 85,
            START.plusHours(2), List.of(member()));
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));
        when(mirrorService.canonicalProps(eq(block), isNull())).thenReturn(Map.of("k", "v"));
        givenMapping("stale-checksum");

        // When — the page shows a different window
        SyncOutcome outcome = service.apply(
            page(START.plusHours(3), START.plusHours(4), null, false));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.REASSERTED);
        verify(blockPort, never()).applyUserEdit(any());
        assertThat(capturedEvent().payload()).contains("frozen history");
    }

    @Test
    @DisplayName("archived page of a live block de-schedules it: delete + mapping removal + NOTION-origin removal event")
    void archived_live_page_deschedules() {
        // Given
        TimeBlockSnapshot block = plannedBlock("USER");
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));
        when(blockPort.deleteLiveBlock(BLOCK_ID)).thenReturn(true);
        givenMapping("any");

        // When
        SyncOutcome outcome = service.apply(new NotionTimeBlockPage(PAGE_ID, EDITED_AT, true,
            null, null, null, null, null, null, null, List.of(), false, null));

        // Then
        assertThat(outcome).isEqualTo(SyncOutcome.DELETED);
        verify(blockPort).deleteLiveBlock(BLOCK_ID);
        verify(syncMappingRepo).deleteByExternalSystemAndId("NOTION", PAGE_ID);
        OutboxEvent staged = capturedEvent();
        assertThat(staged.sourceSystem()).isEqualTo("NOTION");
        assertThat(staged.payload()).contains("DELETED");
    }

    @Test
    @DisplayName("creation from Notion: USER/PLANNED, relation-only — mapped members required, never creates executables")
    void creates_user_planned_block_relation_only() {
        // Given an unmapped page with a Date and one mapped task relation
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.empty());
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", TASK_PAGE_ID))
            .thenReturn(Optional.of(new SyncMapping(UUID.randomUUID(), USER_ID, MEMBER_ID,
                "NOTION", TASK_PAGE_ID, null, "SYNCED", EDITED_AT)));
        when(blockPort.createUserBlock(eq(USER_ID), any(UUID.class), eq(START),
            eq(START.plusMinutes(90)), eq("My block"), eq(List.of(MEMBER_ID))))
            .thenReturn(new TimeBlockEditOutcome(true, List.of(), Set.of(MEMBER_ID), Set.of(),
                Set.of(), Set.of()));
        when(blockPort.findBlock(any(UUID.class)))
            .thenReturn(Optional.of(plannedBlock("USER")));
        when(mirrorService.canonicalProps(any(), isNull())).thenReturn(Map.of("k", "v"));
        when(mirrorService.checksum(eq(PAGE_ID), any())).thenReturn("cs");

        // When
        SyncOutcome outcome = service.apply(new NotionTimeBlockPage(PAGE_ID, EDITED_AT, false,
            "My block", START.toString(), START.plusMinutes(90).toString(), "Planned", "User",
            null, null, List.of(TASK_PAGE_ID), false, null));

        // Then — block created through the port, mapping staged, CREATED event with NOTION origin
        assertThat(outcome).isEqualTo(SyncOutcome.CREATED);
        verify(blockPort).createUserBlock(eq(USER_ID), any(UUID.class), eq(START),
            eq(START.plusMinutes(90)), eq("My block"), eq(List.of(MEMBER_ID)));
        ArgumentCaptor<SyncMapping> mapping = ArgumentCaptor.forClass(SyncMapping.class);
        verify(syncMappingRepo).insert(mapping.capture());
        assertThat(mapping.getValue().externalId()).isEqualTo(PAGE_ID);
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, atLeastOnce()).append(events.capture());
        assertThat(events.getAllValues())
            .anySatisfy(event -> {
                assertThat(event.sourceSystem()).isEqualTo("NOTION");
                assertThat(event.payload()).contains("CREATED");
            });
    }

    @Test
    @DisplayName("creation without a mapped member stays a Notion draft: nothing persisted")
    void creation_without_members_is_skipped() {
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.empty());
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", TASK_PAGE_ID))
            .thenReturn(Optional.empty());

        SyncOutcome outcome = service.apply(new NotionTimeBlockPage(PAGE_ID, EDITED_AT, false,
            "Draft", START.toString(), null, null, null, null, null,
            List.of(TASK_PAGE_ID), false, null));

        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED_ECHO);
        verify(blockPort, never()).createUserBlock(any(), any(), any(), any(), any(), anyList());
        verify(syncMappingRepo, never()).insert(any());
    }

    @Test
    @DisplayName("CA-29: a delivery older than the last synced state is discarded")
    void stale_delivery_is_discarded() {
        givenMapping("cs", EDITED_AT.plusMinutes(10));

        SyncOutcome outcome = service.apply(page(START, START.plusMinutes(90), null, false));

        assertThat(outcome).isEqualTo(SyncOutcome.SKIPPED_STALE);
        verify(blockPort, never()).findBlock(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void givenMapping(String checksum) {
        givenMapping(checksum, EDITED_AT.minusHours(1));
    }

    private void givenMapping(String checksum, OffsetDateTime lastSyncedAt) {
        when(syncMappingRepo.findByExternalSystemAndId("NOTION", PAGE_ID))
            .thenReturn(Optional.of(new SyncMapping(UUID.randomUUID(), USER_ID, BLOCK_ID,
                "NOTION", PAGE_ID, checksum, "SYNCED", lastSyncedAt)));
    }

    private OutboxEvent capturedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, atLeastOnce()).append(captor.capture());
        return captor.getValue();
    }

    private static TimeBlockSnapshot plannedBlock(String origin) {
        return new TimeBlockSnapshot(BLOCK_ID, USER_ID, START, START.plusMinutes(90), "PLANNED",
            origin, "Theme", null, 90, null, null, List.of(member()));
    }

    private static TimeBlockMemberSnapshot member() {
        return new TimeBlockMemberSnapshot(MEMBER_ID, "Task A", 90, 0);
    }

    /** A page whose title is the canonical composition (theme unchanged). */
    private NotionTimeBlockPage page(OffsetDateTime start, OffsetDateTime end, String syncNote,
                                     boolean hasMore) {
        return new NotionTimeBlockPage(PAGE_ID, EDITED_AT, false,
            "Mié 05 · 09:00–10:30 · Theme", start.toString(),
            end != null ? end.toString() : null, "Planned", "Planner", 90.0, null,
            List.of(TASK_PAGE_ID), hasMore, syncNote);
    }
}
