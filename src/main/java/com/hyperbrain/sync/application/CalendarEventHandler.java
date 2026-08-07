package com.hyperbrain.sync.application;

import com.hyperbrain.core.domain.model.ReleaseCause;
import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.core.domain.port.in.ExecutableContainmentService;
import com.hyperbrain.prioritizer.application.OnIngestionPriorityReflector;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.domain.service.SourceAwareMerge;
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
 * <p>Same pipeline as {@link ReminderEventHandler} (HU-09 + ADR-012): parse → checksum →
 * source-aware merge (Apple authority: name, notes, start/end, calendar; status and type are
 * kept — EventKit events carry no completed flag and {@code AGENDA} stays {@code AGENDA}) →
 * {@link DomainChangeProcessor} → single persist → post-upsert priority reflection via
 * {@link OnIngestionPriorityReflector} (#66a, ADR-020 D2) → Outbox. For this APPLE origin the
 * reflector stages no SYSTEM event; the APPLE event carries the fresh score to Notion (see
 * {@link ReminderEventHandler}).
 */
@Component
public class CalendarEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventHandler.class);

    private static final String EXTERNAL_SYSTEM = "APPLE";
    private static final String AGGREGATE_TYPE = "SYNC_APPLE";
    private static final String SYNC_STATUS = "SYNCED";

    private final CoreExecutableRepository executableRepo;
    private final SyncSnapshotRepository snapshotRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final OutboxRepository outboxRepo;
    private final DomainChangeProcessor domainChangeProcessor;
    private final OnIngestionPriorityReflector priorityReflector;
    private final PayloadParser payloadParser;
    private final ExecutableContainmentService containment;
    private final UUID defaultUserId;

    public CalendarEventHandler(
        CoreExecutableRepository executableRepo,
        SyncSnapshotRepository snapshotRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        DomainChangeProcessor domainChangeProcessor,
        OnIngestionPriorityReflector priorityReflector,
        PayloadParser payloadParser,
        ExecutableContainmentService containment,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.executableRepo = executableRepo;
        this.snapshotRepo = snapshotRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.domainChangeProcessor = domainChangeProcessor;
        this.priorityReflector = priorityReflector;
        this.payloadParser = payloadParser;
        this.containment = containment;
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
        ExecutableSnapshot current = existing.isPresent()
            ? snapshotRepo.findExecutable(executableId).orElse(null)
            : null;
        ExecutableSnapshot merged =
            SourceAwareMerge.mergeCalendarEvent(current, executableId, defaultUserId, payload);
        ExecutableSnapshot processed =
            domainChangeProcessor.process(current, merged, ExternalSystem.APPLE);
        executableRepo.upsert(processed);
        // Score the persisted merged row (ADR-020, D2). For an APPLE origin the reflector stages no
        // extra event: the CalendarEventSyncedEvent below already carries the fresh score to Notion.
        priorityReflector.reflect(executableId, ExternalSystem.APPLE);

        if (existing.isEmpty()) {
            syncMappingRepo.insert(buildSyncMapping(executableId, event.entityId(), checksum));
        } else {
            syncMappingRepo.update(buildSyncMapping(executableId, event.entityId(), checksum));
        }

        outboxRepo.append(buildOutboxEvent(event, executableId, "CalendarEventSyncedEvent"));
        log.info("CALENDAR_EVENT {} ({}) persisted as executable {}",
            event.entityId(), event.operation(), executableId);
    }

    /**
     * Applies an inbound calendar-event delete. Since ADR-039 the mapping's {@code local_id} is always
     * a {@code core_executable} — an activity, an agenda entry, or a block, which is now an executable
     * like any other — so there is a single delete path and no second one to route to.
     *
     * <p>An inbound delete is always propagated, regardless of the mapping's age. An earlier version
     * skipped very recent mappings on the theory that an EKEvent's {@code eventIdentifier} could mutate
     * shortly after creation (a spurious {@code DELETED(oldId) + CREATED(newId)} pair). Empirical
     * validation on the Mac Mini disproved it: a manual deletion of 23 "HyperBrain" calendar blocks
     * produced 23 genuine DELETEs and zero re-mapping CREATEs — the ids do not mutate in this setup, so
     * the guard only suppressed real user deletions and left the DB pointing at dead events.
     */
    private void handleDeleted(SentinelEvent event) {
        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isEmpty()) {
            log.warn("CALENDAR_EVENT {} not found in sync_mappings; DELETE event {} has no effect",
                event.entityId(), event.eventId());
            return;
        }

        UUID localId = existing.get().localId();

        if (executableRepo.findById(localId).isEmpty()) {
            log.warn("CALENDAR_EVENT {} DELETE maps to local {}, which no longer exists; no-op",
                event.entityId(), localId);
            return;
        }

        // Let anything the row was holding go BEFORE deleting it (ADR-040 D10). Relying on the
        // database to detach members on cascade mutates rows without passing through the domain and
        // without emitting a single event, so the calendar and Notion keep the hour of a block that no
        // longer exists — silent corruption of the mirrors that never self-corrects because nobody
        // finds out. A user's gesture releases them WITH their hour intact: you deleted a block, you
        // did not ask to lose the time you had set aside for what was in it.
        containment.releaseMembers(localId, ReleaseCause.USER_DETACH, userZone());
        executableRepo.deleteById(localId);
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, event.entityId());
        outboxRepo.append(buildOutboxEvent(event, localId, "CalendarEventDeletedEvent"));
        log.info("CALENDAR_EVENT {} deleted (executable {})", event.entityId(), localId);
    }

    /**
     * The zone the released members' day is reasoned in. A user detach keeps each member's hour, so the
     * zone never actually decides anything on this path — it is required by the operation's signature
     * because the planner's withdrawal, the other caller, does demote the hour to midnight.
     */
    private java.time.ZoneId userZone() {
        return java.time.ZoneOffset.UTC;
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
