package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.service.NotionTimeBlockMapper;
import com.hyperbrain.sync.infrastructure.NotionSyncProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the "mirror one time block to Notion" operation (ADR-038), shared by the outbound
 * propagator, the one-shot backfill and the retention sweep, so all three write with identical
 * canonical properties, checksum recipe and mapping bookkeeping (SRP: the propagator routes
 * events, this class writes pages).
 *
 * <p>Contract highlights:
 * <ul>
 *   <li><b>FOCUS blocks are never mirrored</b>; only {@code origin ∈ {PLANNER, USER}}.</li>
 *   <li><b>Create only while live</b> (anti-resurrection): a terminal block with no mapping —
 *       e.g. one the retention sweep already cleared — never re-creates a page; late events on
 *       swept blocks are no-ops. The backfill's deliberate mirroring of recent {@code SETTLED}
 *       blocks passes {@code allowTerminalCreate}.</li>
 *   <li><b>Human-edit yield</b> ({@link NotionHumanEditGuard}): a full mirror discards when a
 *       person is editing the page; the inbound webhook reconciles.</li>
 *   <li><b>Archived page ≠ failure</b>: Notion answers a PATCH on an archived page with 400
 *       ({@code validation_error} … archived), which the REST adapter maps to
 *       {@link NotionPageNotFoundException}. For a live block that means the user archived the
 *       page and the inbound de-scheduling is in flight — the write is <em>skipped</em> (mapping
 *       kept, no ERROR marker, no retry loop) instead of recreating the page under the user's
 *       hands.</li>
 *   <li>The stored checksum covers the full canonical props (Sync Note included) so the inbound
 *       echo check recomputes it bit-identically; the {@code Tasks} relation is canonicalized
 *       (sorted page ids) on both sides.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTimeBlockMirrorService {

    private static final Logger log = LoggerFactory.getLogger(NotionTimeBlockMirrorService.class);

    private static final String EXTERNAL_SYSTEM = "NOTION";
    private static final String STATUS_SYNCED = "SYNCED";
    private static final String STATUS_ERROR = "ERROR";

    private final SyncMappingRepository syncMappingRepo;
    private final NotionPort notion;
    private final NotionHumanEditGuard humanEditGuard;
    private final NotionSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public NotionTimeBlockMirrorService(
        SyncMappingRepository syncMappingRepo,
        NotionPort notion,
        NotionHumanEditGuard humanEditGuard,
        NotionSyncProperties properties,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.syncMappingRepo = syncMappingRepo;
        this.notion = notion;
        this.humanEditGuard = humanEditGuard;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Mirrors one block state onto its Notion page: creates the page (and its mapping) when the
     * block is live and unmapped, or patches the existing page as a full mirror. Refreshes the
     * mapping checksum + {@code last_synced_at} after every write so the webhook echo is
     * discarded (CA-4/CA-7).
     *
     * @param block               the block state to mirror; FOCUS blocks are skipped
     * @param syncNote            visible conflict note to show, or null to clear the field
     * @param allowTerminalCreate true only on the backfill path, which deliberately creates
     *                            pages for recent {@code SETTLED} blocks
     * @return what happened ({@code CREATED}, {@code UPDATED}) or empty when skipped
     */
    public Optional<Operation> mirror(TimeBlockSnapshot block, String syncNote,
                                      boolean allowTerminalCreate) {
        if (!"PLANNER".equals(block.origin()) && !"USER".equals(block.origin())) {
            log.debug("Block {} has origin {}; never mirrored", block.id(), block.origin());
            return Optional.empty();
        }
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, block.id());
        if (mapping.isEmpty() && block.terminal() && !allowTerminalCreate) {
            log.debug("Terminal block {} has no Notion mapping; create suppressed "
                + "(anti-resurrection)", block.id());
            return Optional.empty();
        }
        Map<String, Object> props = canonicalProps(block, syncNote);
        if (mapping.isEmpty()) {
            String externalId = notion.createPage(properties.getTimeblocksDataSourceId(), props)
                .replace("-", "");
            syncMappingRepo.insert(new SyncMapping(
                UUID.randomUUID(), block.userId(), block.id(), EXTERNAL_SYSTEM,
                externalId, checksum(externalId, props), STATUS_SYNCED, notionClockNow()));
            recordWrite(Operation.CREATED);
            log.info("Notion time-block page {} created for block {}", externalId, block.id());
            return Optional.of(Operation.CREATED);
        }
        String externalId = mapping.get().externalId();
        if (humanEditGuard.hasHumanEditInFlight(externalId, mapping.get())) {
            log.info("Notion time-block page {} has a human edit in flight; mirror of block {} "
                + "discarded, inbound webhook reconciles", externalId, block.id());
            meterRegistry.counter("hyperbrain.sync.notion.skips",
                "reason", "human_edit_in_flight").increment();
            return Optional.empty();
        }
        try {
            notion.updatePage(externalId, props);
        } catch (NotionPageNotFoundException ex) {
            // Archived (400) or gone (404). For a live block the user archived the page and the
            // inbound de-scheduling reconciles; recreating here would resurrect it under their
            // hands, and rethrowing would loop the drain. Skip, keep the mapping, let inbound win.
            log.warn("Notion time-block page {} is archived/gone; mirror of block {} skipped "
                + "(inbound de-scheduling reconciles)", externalId, block.id());
            return Optional.empty();
        }
        syncMappingRepo.update(new SyncMapping(
            mapping.get().id(), mapping.get().userId(), block.id(), EXTERNAL_SYSTEM,
            externalId, checksum(externalId, props), STATUS_SYNCED, notionClockNow()));
        recordWrite(Operation.UPDATED);
        log.info("Notion time-block page {} updated for block {}", externalId, block.id());
        return Optional.of(Operation.UPDATED);
    }

    /**
     * Archives the page of a removed block and deletes its mapping (the {@code removed_block_ids}
     * path and the inbound-emptied-block path). Idempotent: no mapping or an already-archived
     * page are no-ops.
     *
     * @param blockId the removed {@code core_time_block.id}
     */
    public void archiveAndUnmap(UUID blockId) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, blockId);
        if (mapping.isEmpty()) {
            log.debug("Removed block {} has no Notion mapping; nothing to archive", blockId);
            return;
        }
        String externalId = mapping.get().externalId();
        try {
            notion.archivePage(externalId);
        } catch (NotionPageNotFoundException ex) {
            log.info("Notion time-block page {} already gone; removing stale mapping", externalId);
        }
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, externalId);
        recordWrite(Operation.DELETED);
        log.info("Notion time-block page {} archived for removed block {}", externalId, blockId);
    }

    /**
     * Builds the canonical property map of one block, resolving each member's Tasks page id
     * through {@code sync_mappings} (unmapped members omitted, CA-6 pattern).
     */
    public Map<String, Object> canonicalProps(TimeBlockSnapshot block, String syncNote) {
        List<String> taskPageIds = new ArrayList<>(block.members().size());
        for (TimeBlockMemberSnapshot member : block.members()) {
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, member.executableId())
                .ifPresentOrElse(
                    memberMapping -> taskPageIds.add(memberMapping.externalId()),
                    () -> log.debug("Member {} of block {} has no Tasks page; relation omitted",
                        member.executableId(), block.id()));
        }
        return NotionTimeBlockMapper.map(block, taskPageIds, syncNote);
    }

    /** The shared HU-10 checksum recipe over the canonical props (echo discard, CA-4). */
    public String checksum(String externalId, Map<String, Object> props) {
        return ChecksumCalculator.compute(externalId, Operation.UPDATED.name(),
            propertiesJson(props));
    }

    /** Marks the block's mapping {@code ERROR} after a persistent API failure (CA-13/CA-15). */
    public void markError(UUID blockId) {
        meterRegistry.counter("hyperbrain.sync.notion.failures").increment();
        syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, blockId)
            .ifPresent(mapping -> syncMappingRepo.update(new SyncMapping(
                mapping.id(), mapping.userId(), mapping.localId(), EXTERNAL_SYSTEM,
                mapping.externalId(), mapping.lastKnownChecksum(), STATUS_ERROR,
                mapping.lastSyncedAt())));
    }

    private String propertiesJson(Map<String, Object> props) {
        try {
            return objectMapper.writeValueAsString(props);
        } catch (JsonProcessingException ex) {
            // Property maps only contain strings, numbers and booleans; this never happens.
            throw new IllegalStateException("Unserializable Notion property map", ex);
        }
    }

    private void recordWrite(Operation operation) {
        meterRegistry.counter("hyperbrain.sync.notion.writes",
            "operation", operation.name().toLowerCase(Locale.ROOT)).increment();
    }

    /** Minute truncation matches Notion's {@code last_edited_time} (CA-29 comparison width). */
    private static OffsetDateTime notionClockNow() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    }
}
