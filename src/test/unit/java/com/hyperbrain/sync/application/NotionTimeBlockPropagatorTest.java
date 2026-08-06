package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing of the Notion Time Blocks propagator (ADR-038): the planner's day event mirrors the
 * whole day and archives removals; the fine-grained change event mirrors one block, with
 * {@code DELETED} (and a vanished row) converging to the archive path. The page-writing
 * behavior itself is covered in {@code NotionTimeBlockMirrorIT}.
 */
@DisplayName("NotionTimeBlockPropagator — event routing to the block mirror (ADR-038)")
class NotionTimeBlockPropagatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLOCK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REMOVED_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));

    private PlannerTimeBlockPort blockPort;
    private NotionTimeBlockMirrorService mirrorService;
    private NotionTimeBlockPropagator propagator;

    @BeforeEach
    void setUp() {
        blockPort = mock(PlannerTimeBlockPort.class);
        mirrorService = mock(NotionTimeBlockMirrorService.class);
        propagator = new NotionTimeBlockPropagator(blockPort, mirrorService, new ObjectMapper());
    }

    @Test
    @DisplayName("propagator contract: target NOTION; AGENDA_BLOCK and TIME_BLOCK from known origins are eligible")
    void should_propagate_contract() {
        assertThat(propagator.target()).isEqualTo(ExternalSystem.NOTION);
        assertThat(propagator.shouldPropagate(ExternalSystem.SYSTEM, SyncedEntityType.AGENDA_BLOCK)).isTrue();
        assertThat(propagator.shouldPropagate(ExternalSystem.APPLE, SyncedEntityType.TIME_BLOCK)).isTrue();
        assertThat(propagator.shouldPropagate(ExternalSystem.UNKNOWN, SyncedEntityType.TIME_BLOCK)).isFalse();
        assertThat(propagator.shouldPropagate(ExternalSystem.SYSTEM, SyncedEntityType.EXECUTABLE)).isFalse();
    }

    @Test
    @DisplayName("AgendaBlockPlannedEvent: mirrors the day's live blocks and archives removed_block_ids")
    void planned_day_mirrors_and_archives() {
        // Given
        TimeBlockSnapshot block = liveBlock();
        when(blockPort.findLiveBlocksForDay(eq(USER_ID), any(), any()))
            .thenReturn(List.of(block));
        String payload = """
            {"user_id":"%s","target_day":"2026-08-05","zone_id":"America/Bogota",
             "energy_criterion":"x","removed_block_ids":["%s"]}
            """.formatted(USER_ID, REMOVED_ID);

        // When
        propagator.propagate(event("AGENDA_BLOCK", "AgendaBlockPlannedEvent", USER_ID.toString(),
            payload, "SYSTEM"));

        // Then
        verify(mirrorService).mirror(block, null, false);
        verify(mirrorService).archiveAndUnmap(REMOVED_ID);
    }

    @Test
    @DisplayName("TimeBlockChangedEvent DELETED: archives the page and unmaps without re-reading")
    void changed_deleted_archives() {
        String payload = """
            {"block_id":"%s","user_id":"%s","operation":"DELETED","source_system":"APPLE"}
            """.formatted(BLOCK_ID, USER_ID);

        propagator.propagate(event("CORE_TIME_BLOCK", "TimeBlockChangedEvent",
            BLOCK_ID.toString(), payload, "APPLE"));

        verify(mirrorService).archiveAndUnmap(BLOCK_ID);
        verify(blockPort, never()).findBlock(any());
    }

    @Test
    @DisplayName("TimeBlockChangedEvent UPDATED re-reads state and forwards the payload's Sync Note")
    void changed_updated_mirrors_with_note() {
        TimeBlockSnapshot block = liveBlock();
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));
        String payload = """
            {"block_id":"%s","user_id":"%s","operation":"UPDATED","source_system":"SYSTEM",
             "reflection":"CANONICAL_STATE","sync_note":"rejected: WIG block"}
            """.formatted(BLOCK_ID, USER_ID);

        propagator.propagate(event("CORE_TIME_BLOCK", "TimeBlockChangedEvent",
            BLOCK_ID.toString(), payload, "SYSTEM"));

        verify(mirrorService).mirror(block, "rejected: WIG block", false);
    }

    @Test
    @DisplayName("TimeBlockSettledEvent mirrors the settled state onto the existing page")
    void settled_event_mirrors() {
        TimeBlockSnapshot block = liveBlock();
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.of(block));

        propagator.propagate(event("CORE_TIME_BLOCK", "TimeBlockSettledEvent",
            BLOCK_ID.toString(), "{}", "SYSTEM"));

        verify(mirrorService).mirror(eq(block), isNull(), eq(false));
    }

    @Test
    @DisplayName("a vanished block row converges to the archive path (never mirrors a stale snapshot)")
    void vanished_block_archives() {
        when(blockPort.findBlock(BLOCK_ID)).thenReturn(Optional.empty());

        propagator.propagate(event("CORE_TIME_BLOCK", "TimeBlockSettledEvent",
            BLOCK_ID.toString(), "{}", "SYSTEM"));

        verify(mirrorService).archiveAndUnmap(BLOCK_ID);
    }

    private static TimeBlockSnapshot liveBlock() {
        return new TimeBlockSnapshot(BLOCK_ID, USER_ID, START, START.plusMinutes(90), "PLANNED",
            "PLANNER", "Theme", null, 90, null, null,
            List.of(new TimeBlockMemberSnapshot(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), "Task A", 90, 0)));
    }

    private static OutboxEvent event(String aggregateType, String eventType, String aggregateId,
                                     String payload, String source) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload,
            source, OffsetDateTime.now());
    }
}
