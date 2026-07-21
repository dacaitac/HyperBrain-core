package com.hyperbrain.sync.application;

import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
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
import com.hyperbrain.sync.domain.port.out.PlannerBlockDeletionPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.domain.service.ICloudIdMutationGuard;
import com.hyperbrain.sync.domain.service.SourceAwareMerge;
import com.hyperbrain.sync.infrastructure.PayloadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    /**
     * How recently a mapping must have been written for an inbound DELETE to be treated as a suspected
     * iCloud id mutation rather than a real user deletion. Deliberately conservative; a constant until
     * empirical validation on the Mac Mini says whether "HyperBrain" calendar ids mutate and how soon.
     */
    private static final Duration ICLOUD_ID_MUTATION_WINDOW = Duration.ofMinutes(10);

    private final CoreExecutableRepository executableRepo;
    private final SyncSnapshotRepository snapshotRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final OutboxRepository outboxRepo;
    private final DomainChangeProcessor domainChangeProcessor;
    private final OnIngestionPriorityReflector priorityReflector;
    private final PayloadParser payloadParser;
    private final PlannerBlockDeletionPort plannerBlockDeletionPort;
    private final ICloudIdMutationGuard idMutationGuard;
    private final UUID defaultUserId;

    public CalendarEventHandler(
        CoreExecutableRepository executableRepo,
        SyncSnapshotRepository snapshotRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        DomainChangeProcessor domainChangeProcessor,
        OnIngestionPriorityReflector priorityReflector,
        PayloadParser payloadParser,
        PlannerBlockDeletionPort plannerBlockDeletionPort,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.executableRepo = executableRepo;
        this.snapshotRepo = snapshotRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.domainChangeProcessor = domainChangeProcessor;
        this.priorityReflector = priorityReflector;
        this.payloadParser = payloadParser;
        this.plannerBlockDeletionPort = plannerBlockDeletionPort;
        this.idMutationGuard = new ICloudIdMutationGuard(ICLOUD_ID_MUTATION_WINDOW);
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
     * Applies an inbound calendar-event delete. The mapping's {@code local_id} is either a
     * {@code core_executable} (an ACTIVITY/AGENDA event) or a {@code core_time_block} (a morning-agenda
     * block written to Apple by {@code AgendaBlockPropagator}, #13). Executables are resolved first;
     * anything else is routed to the planner-block delete path, which is shielded by the iCloud id
     * mutation guard because a lost planner block is unrecoverable (Apple-only, not mirrored in Notion).
     */
    private void handleDeleted(SentinelEvent event) {
        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isEmpty()) {
            log.warn("CALENDAR_EVENT {} not found in sync_mappings; DELETE event {} has no effect",
                event.entityId(), event.eventId());
            return;
        }

        SyncMapping mapping = existing.get();
        UUID localId = mapping.localId();

        if (executableRepo.findById(localId).isPresent()) {
            executableRepo.deleteById(localId);
            syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, event.entityId());
            outboxRepo.append(buildOutboxEvent(event, localId, "CalendarEventDeletedEvent"));
            log.info("CALENDAR_EVENT {} deleted (executable {})", event.entityId(), localId);
            return;
        }

        deletePlannerBlock(event, mapping, localId);
    }

    private void deletePlannerBlock(SentinelEvent event, SyncMapping mapping, UUID blockId) {
        if (idMutationGuard.withinMutationWindow(mapping.lastSyncedAt(), OffsetDateTime.now())) {
            log.warn("CALENDAR_EVENT {} DELETE ignored: block {} was mapped within the iCloud id "
                    + "mutation window; treating as a spurious id mutation, not a user deletion",
                event.entityId(), blockId);
            return;
        }

        if (plannerBlockDeletionPort.deletePlannedBlock(blockId)) {
            syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, event.entityId());
            log.info("CALENDAR_EVENT {} deleted (planner block {})", event.entityId(), blockId);
        } else {
            log.warn("CALENDAR_EVENT {} DELETE mapped to local {}, which is neither an executable nor "
                + "a deletable PLANNED block; no-op", event.entityId(), blockId);
        }
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
