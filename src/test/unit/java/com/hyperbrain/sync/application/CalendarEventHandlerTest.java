package com.hyperbrain.sync.application;

import com.hyperbrain.core.application.PassthroughDomainChangeProcessor;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.infrastructure.PayloadParser;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("CalendarEventHandler")
class CalendarEventHandlerTest {

    private CoreExecutableRepository executableRepo;
    private SyncSnapshotRepository snapshotRepo;
    private SyncMappingRepository syncMappingRepo;
    private OutboxRepository outboxRepo;
    private CalendarEventHandler handler;

    private static final UUID USER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        executableRepo = mock(CoreExecutableRepository.class);
        snapshotRepo = mock(SyncSnapshotRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        PayloadParser parser = new PayloadParser(new ObjectMapper().registerModule(new JavaTimeModule()));
        handler = new CalendarEventHandler(executableRepo, snapshotRepo, syncMappingRepo,
            outboxRepo, new PassthroughDomainChangeProcessor(), parser, USER_ID);
    }

    @Test
    @DisplayName("CREATED: persists executable with type=ACTIVITY and correct calendar name")
    void created_maps_to_activity() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKEvent-1"))
            .thenReturn(Optional.empty());
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);

        handler.handle(calendarEvent("EKEvent-1", Operation.CREATED, calendarPayload("Work")));

        verify(executableRepo).upsert(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("ACTIVITY");
        assertThat(captor.getValue().name()).isEqualTo("Team meeting");
        assertThat(captor.getValue().sourceCalendar()).isEqualTo("Work");
        verify(outboxRepo).append(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("CREATED: start_time and end_time are mapped correctly")
    void created_maps_times() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKEvent-2"))
            .thenReturn(Optional.empty());
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);

        handler.handle(calendarEvent("EKEvent-2", Operation.CREATED, calendarPayload("Personal")));

        verify(executableRepo).upsert(captor.capture());
        assertThat(captor.getValue().startTime()).isNotNull();
        assertThat(captor.getValue().endTime()).isNotNull();
    }

    @Test
    @DisplayName("UPDATED with same checksum: discards silently")
    void updated_same_checksum_discards() {
        String payload = calendarPayload("Work");
        String checksum = ChecksumCalculator.compute("EKEvent-3", "UPDATED", payload);
        SyncMapping existing = syncMapping("EKEvent-3", UUID.randomUUID(), checksum);
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKEvent-3"))
            .thenReturn(Optional.of(existing));

        handler.handle(calendarEvent("EKEvent-3", Operation.UPDATED, payload));

        verifyNoInteractions(executableRepo, snapshotRepo, outboxRepo);
    }

    @Test
    @DisplayName("UPDATED with different checksum: merges onto the current row — status and type are kept (ADR-012 D1)")
    void updated_different_checksum_merges() {
        UUID localId = UUID.randomUUID();
        SyncMapping existing = syncMapping("EKEvent-4", localId, "stale");
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKEvent-4"))
            .thenReturn(Optional.of(existing));
        // Current row is an AGENDA entity in progress: Apple must not reset either field.
        when(snapshotRepo.findExecutable(localId)).thenReturn(Optional.of(
            ExecutableSnapshotBuilder.snapshot().id(localId).userId(USER_ID)
                .type("AGENDA").status("IN_PROGRESS").build()));
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);

        handler.handle(calendarEvent("EKEvent-4", Operation.UPDATED, calendarPayload("Personal")));

        verify(executableRepo).upsert(captor.capture());
        assertThat(captor.getValue().sourceCalendar()).isEqualTo("Personal");
        assertThat(captor.getValue().id()).isEqualTo(localId);
        assertThat(captor.getValue().type()).isEqualTo("AGENDA");
        assertThat(captor.getValue().status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("DELETED: removes records and appends outbox event")
    void deleted_removes_and_appends_outbox() {
        UUID localId = UUID.randomUUID();
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKEvent-5"))
            .thenReturn(Optional.of(syncMapping("EKEvent-5", localId, "x")));

        handler.handle(calendarEvent("EKEvent-5", Operation.DELETED, null));

        verify(executableRepo).deleteById(localId);
        verify(syncMappingRepo).deleteByExternalSystemAndId("APPLE", "EKEvent-5");
        verify(outboxRepo).append(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("outbox event carries source_system=APPLE for loop protection")
    void outbox_event_source_system_is_apple() {
        when(syncMappingRepo.findByExternalSystemAndId(eq("APPLE"), any()))
            .thenReturn(Optional.empty());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        handler.handle(calendarEvent("EKEvent-6", Operation.CREATED, calendarPayload("Work")));

        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().sourceSystem()).isEqualTo("APPLE");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SentinelEvent calendarEvent(String entityId, Operation op, String payload) {
        return new SentinelEvent(
            "1", UUID.randomUUID().toString(), "APPLE",
            EntityType.CALENDAR_EVENT, entityId, op,
            OffsetDateTime.parse("2026-07-06T10:00:00-05:00"), payload);
    }

    private static String calendarPayload(String calendarName) {
        return """
            {
              "title": "Team meeting",
              "start_time": "2026-07-07T09:00:00-05:00",
              "end_time": "2026-07-07T10:00:00-05:00",
              "all_day": false,
              "notes": null,
              "calendar_id": "EKCalendar-work",
              "calendar_name": "%s",
              "location": null,
              "alarms": []
            }
            """.formatted(calendarName);
    }

    private static SyncMapping syncMapping(String externalId, UUID localId, String checksum) {
        return new SyncMapping(
            UUID.randomUUID(), USER_ID, localId,
            "APPLE", externalId, checksum, "SYNCED", OffsetDateTime.now());
    }
}
