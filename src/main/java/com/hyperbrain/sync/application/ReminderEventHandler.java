package com.hyperbrain.sync.application;

import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.prioritizer.application.OnIngestionPriorityReflector;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.ReminderPayload;
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
 * Handles inbound {@link EntityType#REMINDER} sync events from Apple EventKit.
 *
 * <p>Pipeline per HU-09 + ADR-012:
 * <ol>
 *   <li>Parse the JSON payload into a domain record.
 *   <li>Compute the SHA-256 checksum ({@code entityId + operation + payload}).
 *   <li>Look up the existing {@code sync_mappings} row.
 *   <li>On CREATED/UPDATED: if the checksum matches the stored one, discard silently;
 *       otherwise merge the payload onto the current row ({@link SourceAwareMerge}, Apple
 *       authority fields only), run the {@link DomainChangeProcessor} and persist the final
 *       state once, delegate the post-upsert priority reflection to
 *       {@link OnIngestionPriorityReflector} (#66a), then write to the Transactional Outbox.
 *   <li>On DELETED: remove both {@code core_executable} and {@code sync_mappings}, then
 *       append a deletion event to the Outbox.
 * </ol>
 *
 * <p>The reflector rescores <b>after</b> the upsert (ADR-020, D2) so the Prioritizer scores the
 * persisted merged state, not the stale pre-merge one. For this APPLE origin it stages no extra
 * SYSTEM event: the {@code ReminderSyncedEvent} already carries {@code source_system=APPLE}, which
 * the Notion propagator re-reads post-commit and mirrors — the fresh score rides that event.
 *
 * <p>All writes happen inside the ingestion transaction started by
 * {@code SyncEventIngestionService} — rolling back on any exception lets SQS redeliver.
 */
@Component
public class ReminderEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReminderEventHandler.class);

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
    private final UUID defaultUserId;

    public ReminderEventHandler(
        CoreExecutableRepository executableRepo,
        SyncSnapshotRepository snapshotRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        DomainChangeProcessor domainChangeProcessor,
        OnIngestionPriorityReflector priorityReflector,
        PayloadParser payloadParser,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.executableRepo = executableRepo;
        this.snapshotRepo = snapshotRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.domainChangeProcessor = domainChangeProcessor;
        this.priorityReflector = priorityReflector;
        this.payloadParser = payloadParser;
        this.defaultUserId = defaultUserId;
    }

    @Override
    public EntityType supportedType() {
        return EntityType.REMINDER;
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
        ReminderPayload payload = payloadParser.parseReminder(event.payload());
        String checksum = ChecksumCalculator.compute(
            event.entityId(), event.operation().name(), event.payload());

        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isPresent() && checksum.equals(existing.get().lastKnownChecksum())) {
            log.debug("REMINDER {} unchanged (checksum match), discarding event {}",
                event.entityId(), event.eventId());
            return;
        }

        UUID executableId = existing.map(SyncMapping::localId).orElseGet(UUID::randomUUID);
        ExecutableSnapshot current = existing.isPresent()
            ? snapshotRepo.findExecutable(executableId).orElse(null)
            : null;
        ExecutableSnapshot merged =
            SourceAwareMerge.mergeReminder(current, executableId, defaultUserId, payload);
        ExecutableSnapshot processed =
            domainChangeProcessor.process(current, merged, ExternalSystem.APPLE);
        executableRepo.upsert(processed);
        // Score the persisted merged row (ADR-020, D2). For an APPLE origin the reflector stages no
        // extra event: the ReminderSyncedEvent below already carries the fresh score to Notion.
        priorityReflector.reflect(executableId, ExternalSystem.APPLE);

        if (existing.isEmpty()) {
            syncMappingRepo.insert(buildSyncMapping(executableId, event.entityId(), checksum));
        } else {
            syncMappingRepo.update(buildSyncMapping(executableId, event.entityId(), checksum));
        }

        outboxRepo.append(buildOutboxEvent(event, executableId, "ReminderSyncedEvent"));
        log.info("REMINDER {} ({}) persisted as executable {}", event.entityId(), event.operation(), executableId);
    }

    private void handleDeleted(SentinelEvent event) {
        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isEmpty()) {
            log.warn("REMINDER {} not found in sync_mappings; DELETE event {} has no effect",
                event.entityId(), event.eventId());
            return;
        }

        UUID executableId = existing.get().localId();
        executableRepo.deleteById(executableId);
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, event.entityId());
        outboxRepo.append(buildOutboxEvent(event, executableId, "ReminderDeletedEvent"));
        log.info("REMINDER {} deleted (executable {})", event.entityId(), executableId);
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
