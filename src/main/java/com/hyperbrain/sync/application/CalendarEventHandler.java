package com.hyperbrain.sync.application;

import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.infrastructure.PayloadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles inbound {@link EntityType#CALENDAR_EVENT} sync events from Apple EventKit.
 *
 * <p>Same CRUD pipeline as {@link ReminderEventHandler}: parse → checksum → upsert
 * {@code core_executable} (type=ACTIVITY) + {@code sync_mappings} → Outbox.
 */
@Component
public class CalendarEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventHandler.class);

    private static final String EXTERNAL_SYSTEM = "APPLE";
    private static final String EXECUTABLE_TYPE = "ACTIVITY";
    private static final String AGGREGATE_TYPE = "SYNC_APPLE";
    private static final String SYNC_STATUS = "SYNCED";

    private final CoreExecutableRepository executableRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final OutboxRepository outboxRepo;
    private final PayloadParser payloadParser;
    private final UUID defaultUserId;

    public CalendarEventHandler(
        CoreExecutableRepository executableRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        PayloadParser payloadParser,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.executableRepo = executableRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.payloadParser = payloadParser;
        this.defaultUserId = defaultUserId;
    }

    @Override
    public EntityType supportedType() {
        return EntityType.CALENDAR_EVENT;
    }

    @Override
    public void handle(SentinelEvent event) {
        if (event.operation() == Operation.DELETED) {
            handleDeleted(event);
        } else {
            handleUpsert(event);
        }
    }

    private void handleUpsert(SentinelEvent event) {
        CalendarEventPayload payload = payloadParser.parseCalendarEvent(event.payload());
        String checksum = ChecksumCalculator.compute(
            event.entityId(), event.operation().name(), event.payload());

        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isPresent() && checksum.equals(existing.get().lastKnownChecksum())) {
            log.debug("CALENDAR_EVENT {} unchanged (checksum match), discarding event {}",
                event.entityId(), event.eventId());
            return;
        }

        UUID executableId = existing.map(SyncMapping::localId).orElseGet(UUID::randomUUID);
        CoreExecutable executable = buildExecutable(executableId, payload);

        if (existing.isEmpty()) {
            executableRepo.insert(executable);
            syncMappingRepo.insert(buildSyncMapping(executableId, event.entityId(), checksum));
        } else {
            executableRepo.update(executable);
            syncMappingRepo.update(buildSyncMapping(executableId, event.entityId(), checksum));
        }

        outboxRepo.append(buildOutboxEvent(event, executableId, "CalendarEventSyncedEvent"));
        log.info("CALENDAR_EVENT {} ({}) persisted as executable {}",
            event.entityId(), event.operation(), executableId);
    }

    private void handleDeleted(SentinelEvent event) {
        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isEmpty()) {
            log.warn("CALENDAR_EVENT {} not found in sync_mappings; DELETE event {} has no effect",
                event.entityId(), event.eventId());
            return;
        }

        UUID executableId = existing.get().localId();
        executableRepo.deleteById(executableId);
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, event.entityId());
        outboxRepo.append(buildOutboxEvent(event, executableId, "CalendarEventDeletedEvent"));
        log.info("CALENDAR_EVENT {} deleted (executable {})", event.entityId(), executableId);
    }

    private CoreExecutable buildExecutable(UUID id, CalendarEventPayload payload) {
        return new CoreExecutable(
            id, defaultUserId, payload.title(), EXECUTABLE_TYPE, "TODO",
            payload.startTime(), payload.endTime(), payload.calendarName());
    }

    private SyncMapping buildSyncMapping(UUID localId, String externalId, String checksum) {
        return new SyncMapping(
            UUID.randomUUID(), defaultUserId, localId,
            EXTERNAL_SYSTEM, externalId, checksum, SYNC_STATUS, OffsetDateTime.now());
    }

    private OutboxEvent buildOutboxEvent(SentinelEvent event, UUID executableId, String eventType) {
        String payload = String.format(
            "{\"local_id\":\"%s\",\"entity_id\":\"%s\",\"operation\":\"%s\"}",
            executableId, event.entityId(), event.operation());
        return new OutboxEvent(
            UUID.randomUUID(), AGGREGATE_TYPE, event.entityId(),
            eventType, payload, EXTERNAL_SYSTEM, OffsetDateTime.now());
    }
}
