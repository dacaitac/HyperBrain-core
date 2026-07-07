package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.CoreCycleRepository;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.service.NotionCycleInboundMapper;
import com.hyperbrain.sync.domain.service.NotionCycleMapper;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
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
 * Inbound half of the SyncServ for the Notion Cycles database (HU-14, ADR-011): persists one
 * page state as a {@code core_cycle} upsert, with the idempotency guarantees of CA-4/28/29:
 * <ul>
 *   <li><b>CA-28 (upsert):</b> CREATE vs UPDATE is resolved by the existence of the
 *       {@code sync_mapping}, never by the webhook event type; archived/trashed pages always
 *       resolve to DELETED.
 *   <li><b>CA-29 (monotonicity):</b> a page whose {@code last_edited_time} is not newer than
 *       {@code sync_mappings.last_synced_at} is stale (reordered burst) and is discarded
 *       without writing. Persisting stores the page's own {@code last_edited_time} so the
 *       guard compares Notion timestamps against Notion timestamps.
 *   <li><b>CA-4 (checksum):</b> the canonical Notion property map is regenerated from the
 *       mapped state via {@link NotionCycleMapper} — the same recipe the outbound write-back
 *       uses — so an echo of the HU-10 write, or a webhook with identical state, matches
 *       {@code last_known_checksum} and is discarded silently (CA-20).
 * </ul>
 *
 * <p>Every write appends to the Transactional Outbox with {@code source_system=NOTION}; the
 * loop protection of the propagators keeps it from bouncing back (RF-17). Runs inside the
 * ingestion transaction of the caller.
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionCycleSyncService {

    private static final Logger log = LoggerFactory.getLogger(NotionCycleSyncService.class);

    private static final String EXTERNAL_SYSTEM = "NOTION";
    private static final String STATUS_SYNCED = "SYNCED";
    private static final String AGGREGATE_TYPE = "CORE_CYCLE";

    private final CoreCycleRepository cycleRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final OutboxRepository outboxRepo;
    private final NotionPort notion;
    private final NotionPageParser pageParser;
    private final ObjectMapper objectMapper;
    private final UUID defaultUserId;

    public NotionCycleSyncService(
        CoreCycleRepository cycleRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        NotionPort notion,
        NotionPageParser pageParser,
        ObjectMapper objectMapper,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.cycleRepo = cycleRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.notion = notion;
        this.pageParser = pageParser;
        this.objectMapper = objectMapper;
        this.defaultUserId = defaultUserId;
    }

    /**
     * Applies one Cycles page state to the domain.
     *
     * @param page the parsed page
     * @return what was done with the state
     */
    public SyncOutcome apply(NotionCyclePage page) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, page.pageId());
        if (page.archived()) {
            return deleteMapped(page.pageId(), mapping);
        }
        if (isStale(page.lastEditedTime(), mapping)) {
            log.info("CYCLE page {} is stale (last_edited_time <= last_synced_at); discarded (CA-29)",
                page.pageId());
            return SyncOutcome.SKIPPED_STALE;
        }

        UUID localId = mapping.map(SyncMapping::localId).orElseGet(UUID::randomUUID);
        CycleSnapshot snapshot = NotionCycleInboundMapper.toSnapshot(page, localId, defaultUserId);
        Map<String, Object> canonicalProps = NotionCycleMapper.map(snapshot);
        if (mapping.isPresent()
            && ChecksumSupport.matches(mapping.get().lastKnownChecksum(), page.pageId(),
                canonicalProps, objectMapper)) {
            log.debug("CYCLE page {} unchanged (checksum match); discarded (CA-4)", page.pageId());
            return SyncOutcome.SKIPPED_ECHO;
        }

        cycleRepo.upsert(snapshot);
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
        appendOutbox(localId, page.pageId(),
            operation == Operation.CREATED ? "CycleCreatedEvent" : "CycleUpdatedEvent", operation);
        log.info("CYCLE page {} ({}) persisted as cycle {}", page.pageId(), operation, localId);
        return operation == Operation.CREATED ? SyncOutcome.CREATED : SyncOutcome.UPDATED;
    }

    /**
     * Removes the cycle mapped to a page that is archived, trashed or no longer accessible.
     *
     * @param pageId normalized Notion page id
     * @return {@link SyncOutcome#DELETED}, also when there was nothing to delete
     */
    public SyncOutcome deleteByExternalId(String pageId) {
        return deleteMapped(pageId,
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, pageId));
    }

    /**
     * Resolves the local cycle id for a task's {@code Cycle} relation (CA-5): a mapped cycle
     * resolves directly; an unmapped one is imported from the Cycles database first, so the
     * relation always resolves. Returns null when the page vanished or is archived.
     *
     * @param cycleExternalId normalized Notion page id of the cycle, or null
     * @return the local {@code core_cycle} id, or null when unresolvable
     */
    public UUID resolveOrImport(String cycleExternalId) {
        if (cycleExternalId == null) {
            return null;
        }
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, cycleExternalId);
        if (mapping.isPresent()) {
            return mapping.get().localId();
        }
        NotionCyclePage page;
        try {
            page = pageParser.parseCycle(objectMapper.readTree(notion.retrievePage(cycleExternalId)));
        } catch (NotionPageNotFoundException ex) {
            log.warn("Cycle page {} referenced by a task no longer exists; relation omitted",
                cycleExternalId);
            return null;
        } catch (JsonProcessingException ex) {
            log.warn("Cycle page {} returned unparseable JSON; relation omitted", cycleExternalId, ex);
            return null;
        }
        if (page.archived()) {
            log.info("Cycle page {} is archived; relation omitted", cycleExternalId);
            return null;
        }
        apply(page);
        return syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, cycleExternalId)
            .map(SyncMapping::localId)
            .orElse(null);
    }

    private SyncOutcome deleteMapped(String pageId, Optional<SyncMapping> mapping) {
        if (mapping.isEmpty()) {
            log.debug("CYCLE page {} has no mapping; DELETE needs no effect", pageId);
            return SyncOutcome.DELETED;
        }
        UUID localId = mapping.get().localId();
        cycleRepo.deleteById(localId);
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, pageId);
        appendOutbox(localId, pageId, "CycleDeletedEvent", Operation.DELETED);
        log.info("CYCLE page {} deleted (cycle {})", pageId, localId);
        return SyncOutcome.DELETED;
    }

    private void appendOutbox(UUID localId, String pageId, String eventType, Operation operation) {
        String payload = String.format(
            "{\"external_id\":\"%s\",\"operation\":\"%s\"}", pageId, operation);
        outboxRepo.append(new OutboxEvent(UUID.randomUUID(), AGGREGATE_TYPE, localId.toString(),
            eventType, payload, EXTERNAL_SYSTEM, OffsetDateTime.now()));
    }

    private static boolean isStale(OffsetDateTime lastEditedTime, Optional<SyncMapping> mapping) {
        return mapping.isPresent()
            && lastEditedTime != null
            && mapping.get().lastSyncedAt() != null
            && !lastEditedTime.isAfter(mapping.get().lastSyncedAt());
    }
}
