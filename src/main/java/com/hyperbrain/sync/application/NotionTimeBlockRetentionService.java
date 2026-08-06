package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.service.NotionSchema;
import com.hyperbrain.sync.infrastructure.NotionSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Retention sweep of the Notion Time Blocks mirror (ADR-038 condition 5): terminal blocks whose
 * settlement is older than the configured window (floor 14 days) disappear from Notion in a
 * fixed, idempotent, resumable order — <b>clear the Tasks relation → archive the page → delete
 * the mapping</b>. Clearing the relation first keeps the archived page from lingering inside
 * every task's "Time Blocks" synced property; deleting the mapping last means a mid-run crash
 * simply repeats the (idempotent) Notion calls on the next pass.
 *
 * <p>The sweep <b>never touches PostgreSQL domain rows</b> — {@code core_time_block} history is
 * kept forever; only the Notion presentation and the {@code sync_mapping} go. Once the mapping
 * is gone the anti-resurrection guard in {@link NotionTimeBlockMirrorService} (create only while
 * live) guarantees a late event on the swept block never recreates the page.
 *
 * <p>Failures are per-block: a Notion outage mid-sweep logs and moves on; the next scheduled
 * pass retries the leftovers. Scheduled daily off-peak, cadence tunable.
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTimeBlockRetentionService {

    private static final Logger log = LoggerFactory.getLogger(NotionTimeBlockRetentionService.class);

    private static final String EXTERNAL_SYSTEM = "NOTION";

    private final PlannerTimeBlockPort blockPort;
    private final SyncMappingRepository syncMappingRepo;
    private final NotionPort notion;
    private final NotionSyncProperties properties;

    public NotionTimeBlockRetentionService(
        PlannerTimeBlockPort blockPort,
        SyncMappingRepository syncMappingRepo,
        NotionPort notion,
        NotionSyncProperties properties
    ) {
        this.blockPort = blockPort;
        this.syncMappingRepo = syncMappingRepo;
        this.notion = notion;
        this.properties = properties;
    }

    /** Daily off-peak trigger; the sweep itself is idempotent and re-runnable at any time. */
    @Scheduled(cron = "${app.sync.notion.timeblocks-retention-cron:0 20 4 * * *}")
    public void sweepScheduled() {
        sweep(OffsetDateTime.now());
    }

    /**
     * Sweeps every mirrored terminal block settled before {@code now − retention}.
     *
     * @param now the reference instant
     * @return how many pages were fully swept in this pass
     */
    public int sweep(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusDays(properties.getTimeblocksRetentionDays());
        List<UUID> candidates = blockPort.findTerminalBlockIdsSettledBefore(cutoff);
        int swept = 0;
        for (UUID blockId : candidates) {
            Optional<SyncMapping> mapping =
                syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, blockId);
            if (mapping.isEmpty()) {
                continue; // never mirrored, or already swept
            }
            try {
                sweepOne(blockId, mapping.get().externalId());
                swept++;
            } catch (NotionApiException ex) {
                log.error("Retention sweep of block {} failed; will retry next pass", blockId, ex);
            }
        }
        if (swept > 0) {
            log.info("Notion time-block retention sweep archived {} page(s) older than {} day(s)",
                swept, properties.getTimeblocksRetentionDays());
        }
        return swept;
    }

    private void sweepOne(UUID blockId, String externalId) {
        try {
            notion.updatePage(externalId,
                Map.of(NotionSchema.PROP_TASKS, Map.of("relation", List.of())));
            notion.archivePage(externalId);
        } catch (NotionPageNotFoundException ex) {
            log.debug("Notion time-block page {} already archived/gone; sweep finishes the mapping",
                externalId);
        }
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, externalId);
        log.info("Notion time-block page {} swept (block {}, relation cleared → archived → unmapped)",
            externalId, blockId);
    }
}
