package com.hyperbrain.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.domain.service.NotionTaskMapper;
import com.hyperbrain.sync.domain.service.SourceAwareMerge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound half of the SyncServ for the Notion Tasks database (HU-14): applies one page state
 * to the domain. Shares the idempotency design of {@link NotionCycleSyncService} — CA-28
 * upsert by mapping existence, CA-29 monotonicity guard on {@code last_edited_time}, CA-4
 * checksum discard over the canonical property map regenerated with {@link NotionTaskMapper}
 * (the HU-10 recipe, so outbound echoes match).
 *
 * <p>Write semantics (ADR-012): the page is merged onto the current row with
 * {@code SourceAwareMerge} (Notion authority fields + loss-aware projection — an untouched
 * {@code Not started} never regresses {@code PLANNED}/{@code WAITING}), the
 * {@code DomainChangeProcessor} applies derived business rules, and the final state is
 * persisted once. {@code cycle} and {@code parent} are always accepted from Notion (CA-6):
 * an unmapped cycle is imported from the Cycles database first (CA-5); an unmapped parent
 * keeps the current link with a warning — the next webhook or backfill repairs it.
 *
 * <p>Every write appends to the Transactional Outbox with {@code source_system=NOTION}: the
 * Apple propagator picks it up for ACTIVITY/TASK write-back (HU-09c) while the Notion
 * propagator's loop protection ignores it (RF-17). Runs inside the caller's ingestion
 * transaction.
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTaskSyncService {

    private static final Logger log = LoggerFactory.getLogger(NotionTaskSyncService.class);

    private static final String EXTERNAL_SYSTEM = "NOTION";
    private static final String STATUS_SYNCED = "SYNCED";
    private static final String AGGREGATE_TYPE = "CORE_EXECUTABLE";

    private final CoreExecutableRepository executableRepo;
    private final SyncSnapshotRepository snapshotRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final OutboxRepository outboxRepo;
    private final NotionCycleSyncService cycleSyncService;
    private final DomainChangeProcessor domainChangeProcessor;
    private final ObjectMapper objectMapper;
    private final UUID defaultUserId;

    public NotionTaskSyncService(
        CoreExecutableRepository executableRepo,
        SyncSnapshotRepository snapshotRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        NotionCycleSyncService cycleSyncService,
        DomainChangeProcessor domainChangeProcessor,
        ObjectMapper objectMapper,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.executableRepo = executableRepo;
        this.snapshotRepo = snapshotRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.cycleSyncService = cycleSyncService;
        this.domainChangeProcessor = domainChangeProcessor;
        this.objectMapper = objectMapper;
        this.defaultUserId = defaultUserId;
    }

    /**
     * Applies one Tasks page state to the domain.
     *
     * @param page the parsed page
     * @return what was done with the state
     */
    public SyncOutcome apply(NotionTaskPage page) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, page.pageId());
        if (page.archived()) {
            return deleteMapped(page.pageId(), mapping);
        }
        if (isStale(page.lastEditedTime(), mapping)) {
            log.info("TASK page {} is stale (last_edited_time < last_synced_at); discarded (CA-29)",
                page.pageId());
            return SyncOutcome.SKIPPED_STALE;
        }

        UUID localId = mapping.map(SyncMapping::localId).orElseGet(UUID::randomUUID);
        ExecutableSnapshot current = mapping.isPresent()
            ? snapshotRepo.findExecutable(localId).orElse(null)
            : null;
        UUID cycleId = cycleSyncService.resolveOrImport(page.cycleRelationId());
        UUID parentId = resolveParent(page.parentRelationId());
        ExecutableSnapshot merged =
            SourceAwareMerge.mergeNotionTask(current, page, localId, defaultUserId, cycleId, parentId);
        ExecutableSnapshot snapshot =
            domainChangeProcessor.process(current, merged, ExternalSystem.NOTION);
        Map<String, Object> canonicalProps =
            NotionTaskMapper.map(snapshot, page.cycleRelationId(), page.parentRelationId());
        if (mapping.isPresent()
            && ChecksumSupport.matches(mapping.get().lastKnownChecksum(), page.pageId(),
                canonicalProps, objectMapper)) {
            log.debug("TASK page {} unchanged (checksum match); discarded (CA-4)", page.pageId());
            return SyncOutcome.SKIPPED_ECHO;
        }

        executableRepo.upsert(snapshot);
        String checksum = ChecksumSupport.compute(page.pageId(), canonicalProps, objectMapper);
        OffsetDateTime syncedAt = page.lastEditedTime() != null ? page.lastEditedTime() : OffsetDateTime.now();
        Operation operation = mapping.isEmpty() ? Operation.CREATED : Operation.UPDATED;
        if (mapping.isEmpty()) {
            syncMappingRepo.insert(new SyncMapping(UUID.randomUUID(), defaultUserId, localId,
                EXTERNAL_SYSTEM, page.pageId(), checksum, STATUS_SYNCED, syncedAt));
        } else {
            syncMappingRepo.update(new SyncMapping(mapping.get().id(), mapping.get().userId(), localId,
                EXTERNAL_SYSTEM, page.pageId(), checksum, STATUS_SYNCED, syncedAt));
        }
        appendOutbox(localId, page.pageId(), snapshot.type(),
            operation == Operation.CREATED ? "ExecutableCreatedEvent" : "ExecutableUpdatedEvent",
            operation);
        log.info("TASK page {} ({}) persisted as executable {}", page.pageId(), operation, localId);
        return operation == Operation.CREATED ? SyncOutcome.CREATED : SyncOutcome.UPDATED;
    }

    /**
     * Removes the executable mapped to a page that is archived, trashed or no longer
     * accessible, plus its mapping; the Apple write-back deletes the mapped satellite entity
     * downstream (CA-7).
     *
     * @param pageId normalized Notion page id
     * @return {@link SyncOutcome#DELETED}, also when there was nothing to delete
     */
    public SyncOutcome deleteByExternalId(String pageId) {
        return deleteMapped(pageId,
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, pageId));
    }

    private SyncOutcome deleteMapped(String pageId, Optional<SyncMapping> mapping) {
        if (mapping.isEmpty()) {
            log.debug("TASK page {} has no mapping; DELETE needs no effect", pageId);
            return SyncOutcome.DELETED;
        }
        UUID localId = mapping.get().localId();
        // The executable type travels in the outbox payload so the Apple propagator can derive
        // the WriteCommand type (REMINDER vs CALENDAR_EVENT) after the row is gone.
        String executableType = executableRepo.findById(localId)
            .map(CoreExecutable::type)
            .orElse(null);
        executableRepo.deleteById(localId);
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, pageId);
        appendOutbox(localId, pageId, executableType, "ExecutableDeletedEvent", Operation.DELETED);
        log.info("TASK page {} deleted (executable {})", pageId, localId);
        return SyncOutcome.DELETED;
    }

    private UUID resolveParent(String parentExternalId) {
        if (parentExternalId == null) {
            return null;
        }
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, parentExternalId);
        if (mapping.isEmpty()) {
            log.warn("Parent task page {} is not mapped yet; relation omitted", parentExternalId);
            return null;
        }
        return mapping.get().localId();
    }

    private void appendOutbox(UUID localId, String pageId, String executableType,
                              String eventType, Operation operation) {
        String payload = String.format(
            "{\"external_id\":\"%s\",\"operation\":\"%s\"%s}",
            pageId, operation,
            executableType != null ? ",\"type\":\"" + executableType + "\"" : "");
        outboxRepo.append(new OutboxEvent(UUID.randomUUID(), AGGREGATE_TYPE, localId.toString(),
            eventType, payload, EXTERNAL_SYSTEM, OffsetDateTime.now()));
    }

    /**
     * CA-29 monotonicity guard. Strictly-older only: Notion truncates {@code last_edited_time}
     * to the minute, so successive edits within the same minute share one timestamp — an equal
     * timestamp must fall through to the checksum discard (CA-4), or those edits are lost.
     */
    private static boolean isStale(OffsetDateTime lastEditedTime, Optional<SyncMapping> mapping) {
        return mapping.isPresent()
            && lastEditedTime != null
            && mapping.get().lastSyncedAt() != null
            && lastEditedTime.isBefore(mapping.get().lastSyncedAt());
    }
}
