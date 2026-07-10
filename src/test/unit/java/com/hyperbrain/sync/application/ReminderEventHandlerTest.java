package com.hyperbrain.sync.application;

import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.prioritizer.application.OnIngestionPriorityReflector;
import com.hyperbrain.shared.messaging.ExternalSystem;
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

@DisplayName("ReminderEventHandler")
class ReminderEventHandlerTest {

    private CoreExecutableRepository executableRepo;
    private SyncSnapshotRepository snapshotRepo;
    private SyncMappingRepository syncMappingRepo;
    private OutboxRepository outboxRepo;
    private OnIngestionPriorityReflector priorityReflector;
    private ReminderEventHandler handler;

    private static final UUID USER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        executableRepo = mock(CoreExecutableRepository.class);
        snapshotRepo = mock(SyncSnapshotRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        priorityReflector = mock(OnIngestionPriorityReflector.class);
        PayloadParser parser = new PayloadParser(new ObjectMapper().registerModule(new JavaTimeModule()));
        handler = new ReminderEventHandler(executableRepo, snapshotRepo, syncMappingRepo,
            outboxRepo, new EndTimeInvariantRule()::apply, priorityReflector, parser, USER_ID);
    }

    @Test
    @DisplayName("CREATED: upserts executable + inserts sync_mapping and appends outbox event")
    void created_upserts_and_appends_outbox() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-1"))
            .thenReturn(Optional.empty());

        handler.handle(reminderEvent("EKReminder-1", Operation.CREATED, reminderPayload(false)));

        verify(executableRepo).upsert(any(ExecutableSnapshot.class));
        verify(syncMappingRepo).insert(any(SyncMapping.class));
        verify(outboxRepo).append(any(OutboxEvent.class));
        verifyNoInteractions(snapshotRepo);
    }

    @Test
    @DisplayName("CREATED: executable is persisted with type=TASK and correct name")
    void created_maps_title_to_name() {
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-1"))
            .thenReturn(Optional.empty());
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);

        handler.handle(reminderEvent("EKReminder-1", Operation.CREATED, reminderPayload(false)));

        verify(executableRepo).upsert(captor.capture());
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
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);

        handler.handle(reminderEvent("EKReminder-2", Operation.CREATED, reminderPayload(true)));

        verify(executableRepo).upsert(captor.capture());
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

        verifyNoInteractions(executableRepo, snapshotRepo, outboxRepo);
        verify(syncMappingRepo, never()).update(any());
    }

    @Test
    @DisplayName("UPDATED with different checksum: merges onto the current row and updates sync_mapping (ADR-012 D1)")
    void updated_different_checksum_merges_and_updates() {
        UUID localId = UUID.randomUUID();
        SyncMapping existing = syncMapping("EKReminder-4", localId, "old-checksum");
        when(syncMappingRepo.findByExternalSystemAndId("APPLE", "EKReminder-4"))
            .thenReturn(Optional.of(existing));
        // Current row holds Notion-owned planning data that Apple must not destroy.
        when(snapshotRepo.findExecutable(localId)).thenReturn(Optional.of(
            ExecutableSnapshotBuilder.snapshot().id(localId).userId(USER_ID)
                .name("Old name").status("IN_PROGRESS").priorityScore(0.7)
                .startTime(OffsetDateTime.parse("2026-07-05T08:00:00-05:00"))
                .build()));
        ArgumentCaptor<ExecutableSnapshot> captor = ArgumentCaptor.forClass(ExecutableSnapshot.class);

        handler.handle(reminderEvent("EKReminder-4", Operation.UPDATED, reminderPayload(false)));

        verify(executableRepo).upsert(captor.capture());
        ExecutableSnapshot merged = captor.getValue();
        assertThat(merged.name()).isEqualTo("Buy groceries");
        assertThat(merged.status()).isEqualTo("IN_PROGRESS");
        assertThat(merged.priorityScore()).isEqualTo(0.7);
        // Apple due date (09:00) differs from stored startTime (08:00) → Apple takes authority over startTime (DR-01)
        assertThat(merged.startTime()).isEqualTo(OffsetDateTime.parse("2026-07-05T09:00:00-05:00"));
        assertThat(merged.endTime()).isNull();
        verify(syncMappingRepo).update(any(SyncMapping.class));
        verify(outboxRepo).append(any(OutboxEvent.class));
        verify(syncMappingRepo, never()).insert(any());
        // #66a: the post-upsert priority reflection is delegated to the shared reflector with the
        // APPLE origin (which stages no extra SYSTEM event — its own APPLE event carries the score).
        verify(priorityReflector).reflect(localId, ExternalSystem.APPLE);
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
