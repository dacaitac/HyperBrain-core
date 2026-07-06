package com.hyperbrain.sync.application;

import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.infrastructure.PayloadParser;
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

@DisplayName("ReminderEventHandler")
class ReminderEventHandlerTest {

    private CoreExecutableRepository executableRepo;
    private SyncMappingRepository syncMappingRepo;
    private OutboxRepository outboxRepo;
    private ReminderEventHandler handler;

    private static final UUID USER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        executableRepo = mock(CoreExecutableRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        PayloadParser parser = new PayloadParser(new ObjectMapper().registerModule(new JavaTimeModule()));
        handler = new ReminderEventHandler(executableRepo, syncMappingRepo, outboxRepo, parser, USER_ID);
    }

    @Test
    @DisplayName("CREATED: inserts executable + sync_mapping and appends outbox event")
    void created_inserts_and_appends_outbox() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-1"))
            .thenReturn(Optional.empty());

        handler.handle(reminderEvent("EKReminder-1", Operation.CREATED, reminderPayload(false)));

        verify(executableRepo).insert(any(CoreExecutable.class));
        verify(syncMappingRepo).insert(any(SyncMapping.class));
        verify(outboxRepo).append(any(OutboxEvent.class));
        verify(executableRepo, never()).update(any());
    }

    @Test
    @DisplayName("CREATED: executable is inserted with type=TASK and correct name")
    void created_maps_title_to_name() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-1"))
            .thenReturn(Optional.empty());
        ArgumentCaptor<CoreExecutable> captor = ArgumentCaptor.forClass(CoreExecutable.class);

        handler.handle(reminderEvent("EKReminder-1", Operation.CREATED, reminderPayload(false)));

        verify(executableRepo).insert(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Buy groceries");
        assertThat(captor.getValue().type()).isEqualTo("TASK");
        assertThat(captor.getValue().status()).isEqualTo("TODO");
        assertThat(captor.getValue().sourceCalendar()).isEqualTo("HyperBrain");
    }

    @Test
    @DisplayName("CREATED: completed=true maps to status=DONE")
    void completed_reminder_maps_to_done_status() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-2"))
            .thenReturn(Optional.empty());
        ArgumentCaptor<CoreExecutable> captor = ArgumentCaptor.forClass(CoreExecutable.class);

        handler.handle(reminderEvent("EKReminder-2", Operation.CREATED, reminderPayload(true)));

        verify(executableRepo).insert(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("UPDATED with same checksum: discards silently (no DB writes)")
    void updated_same_checksum_discards_silently() {
        String payload = reminderPayload(false);
        String checksum = ChecksumCalculator.compute("EKReminder-3", "UPDATED", payload);
        SyncMapping existing = syncMapping("EKReminder-3", UUID.randomUUID(), checksum);
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-3"))
            .thenReturn(Optional.of(existing));

        handler.handle(reminderEvent("EKReminder-3", Operation.UPDATED, payload));

        verifyNoInteractions(executableRepo, outboxRepo);
        verify(syncMappingRepo, never()).update(any());
    }

    @Test
    @DisplayName("UPDATED with different checksum: updates executable and sync_mapping")
    void updated_different_checksum_updates() {
        UUID localId = UUID.randomUUID();
        SyncMapping existing = syncMapping("EKReminder-4", localId, "old-checksum");
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-4"))
            .thenReturn(Optional.of(existing));

        handler.handle(reminderEvent("EKReminder-4", Operation.UPDATED, reminderPayload(false)));

        verify(executableRepo).update(any(CoreExecutable.class));
        verify(syncMappingRepo).update(any(SyncMapping.class));
        verify(outboxRepo).append(any(OutboxEvent.class));
        verify(executableRepo, never()).insert(any());
    }

    @Test
    @DisplayName("DELETED: removes executable and sync_mapping, appends outbox event")
    void deleted_removes_records_and_appends_outbox() {
        UUID localId = UUID.randomUUID();
        SyncMapping existing = syncMapping("EKReminder-5", localId, "any-checksum");
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-5"))
            .thenReturn(Optional.of(existing));

        handler.handle(reminderEvent("EKReminder-5", Operation.DELETED, null));

        verify(executableRepo).deleteById(localId);
        verify(syncMappingRepo).deleteByExternalSystemAndId("APPLE", "EKReminder-5");
        verify(outboxRepo).append(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("DELETED: no-op when entity is unknown (not in sync_mappings)")
    void deleted_unknown_entity_is_noop() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-99"))
            .thenReturn(Optional.empty());

        handler.handle(reminderEvent("EKReminder-99", Operation.DELETED, null));

        verifyNoInteractions(executableRepo, outboxRepo);
        verify(syncMappingRepo, never()).deleteByExternalSystemAndId(any(), any());
    }

    @Test
    @DisplayName("outbox event carries source_system=APPLE for loop protection (RF-17)")
    void outbox_event_has_apple_source_system() {
        when(syncMappingRepo.findByExternalSystemAndId(eq("APPLE"), any()))
            .thenReturn(Optional.empty());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        handler.handle(reminderEvent("EKReminder-6", Operation.CREATED, reminderPayload(false)));

        verify(outboxRepo).append(captor.capture());
        assertThat(captor.getValue().sourceSystem()).isEqualTo("APPLE");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SentinelEvent reminderEvent(String entityId, Operation op, String payload) {
        return new SentinelEvent(
            "1", UUID.randomUUID().toString(), "APPLE",
            EntityType.REMINDER, entityId, op,
            OffsetDateTime.parse("2026-07-04T15:30:00-05:00"), payload);
    }

    private static String reminderPayload(boolean completed) {
        return """
            {
              "title": "Buy groceries",
              "notes": null,
              "due_date": "2026-07-05T09:00:00-05:00",
              "completed": %s,
              "priority": 0,
              "list_id": "EKCalendar-abc",
              "list_name": "HyperBrain",
              "alarms": []
            }
            """.formatted(completed);
    }

    private static SyncMapping syncMapping(String externalId, UUID localId, String checksum) {
        return new SyncMapping(
            UUID.randomUUID(), USER_ID, localId,
            "APPLE", externalId, checksum, "SYNCED", OffsetDateTime.now());
    }
}
