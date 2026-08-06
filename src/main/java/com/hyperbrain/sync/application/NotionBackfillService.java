package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import com.hyperbrain.sync.infrastructure.NotionSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Initial import of the Notion Tasks and Cycles databases (HU-14 CA-8): queries every page of
 * both data sources and applies each state through the same upsert path the webhooks use, so
 * the run is idempotent and converges with concurrent webhook deliveries (CA-28) — re-running
 * it, or receiving a webhook for an already-backfilled page, never duplicates entities. The
 * manual reconciliation tool when webhook deliveries were lost (ADR-011, analogous to the
 * SentinelAPI backfill).
 *
 * <p>Cycles import first so every task's {@code Cycle} relation resolves without extra page
 * fetches. Archived/trashed pages returned by the query are skipped (nothing local to delete
 * on first import; a mapped one is removed through the regular DELETE path).
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionBackfillService {

    private static final Logger log = LoggerFactory.getLogger(NotionBackfillService.class);

    private final NotionPort notion;
    private final NotionPageParser pageParser;
    private final NotionTaskSyncService taskSyncService;
    private final NotionCycleSyncService cycleSyncService;
    private final PlannerTimeBlockPort blockPort;
    private final NotionTimeBlockMirrorService timeBlockMirrorService;
    private final NotionSyncProperties properties;
    private final ObjectMapper objectMapper;

    public NotionBackfillService(
        NotionPort notion,
        NotionPageParser pageParser,
        NotionTaskSyncService taskSyncService,
        NotionCycleSyncService cycleSyncService,
        PlannerTimeBlockPort blockPort,
        NotionTimeBlockMirrorService timeBlockMirrorService,
        NotionSyncProperties properties,
        ObjectMapper objectMapper
    ) {
        this.notion = notion;
        this.pageParser = pageParser;
        this.taskSyncService = taskSyncService;
        this.cycleSyncService = cycleSyncService;
        this.blockPort = blockPort;
        this.timeBlockMirrorService = timeBlockMirrorService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Result of one backfill run.
     *
     * @param cycles  outcome counts of the Cycles import
     * @param tasks   outcome counts of the Tasks import
     */
    public record BackfillSummary(Map<SyncOutcome, Integer> cycles, Map<SyncOutcome, Integer> tasks) {
    }

    /**
     * Imports the current state of both databases. Runs in one transaction: a mid-run failure
     * rolls back and the run can simply be repeated.
     *
     * @return outcome counts per database
     */
    @Transactional
    public BackfillSummary backfill() {
        Map<SyncOutcome, Integer> cycles = new EnumMap<>(SyncOutcome.class);
        for (String pageJson : notion.queryAllPages(properties.getCyclesDataSourceId())) {
            NotionCyclePage page = pageParser.parseCycle(parse(pageJson));
            record(cycles, cycleSyncService.apply(page));
        }
        Map<SyncOutcome, Integer> tasks = new EnumMap<>(SyncOutcome.class);
        for (String pageJson : notion.queryAllPages(properties.getTasksDataSourceId())) {
            NotionTaskPage page = pageParser.parseTask(parse(pageJson));
            record(tasks, taskSyncService.apply(page));
        }
        log.info("Notion backfill finished: cycles={} tasks={}", cycles, tasks);
        return new BackfillSummary(cycles, tasks);
    }

    /**
     * One-shot outbound backfill of the Time Blocks mirror (ADR-038): mirrors every live block
     * plus the {@code SETTLED} ones inside the retention window ({@code EXPIRED} never travels).
     * Idempotent — a mapped block updates its page in place, an unmapped one is created — so
     * the run converges with concurrent outbox drains and can simply be repeated.
     *
     * <p>Deliberately <b>not</b> transactional: each page create commits its mapping right away,
     * so a mid-run failure never rolls mappings back under already-created pages (a rerun would
     * then duplicate them). Direction note: unlike {@link #backfill()} (Notion → Core), this
     * pass writes Core → Notion.
     *
     * @return outcome counts ({@code CREATED}/{@code UPDATED}/{@code SKIPPED_ECHO} for skips)
     */
    public Map<SyncOutcome, Integer> backfillTimeBlocks() {
        OffsetDateTime settledAfter =
            OffsetDateTime.now().minusDays(properties.getTimeblocksRetentionDays());
        Map<SyncOutcome, Integer> counts = new EnumMap<>(SyncOutcome.class);
        for (TimeBlockSnapshot block : blockPort.findMirrorableBlocks(settledAfter)) {
            SyncOutcome outcome = timeBlockMirrorService.mirror(block, null, true)
                .map(op -> switch (op) {
                    case CREATED -> SyncOutcome.CREATED;
                    default -> SyncOutcome.UPDATED;
                })
                .orElse(SyncOutcome.SKIPPED_ECHO);
            record(counts, outcome);
        }
        log.info("Notion time-block backfill finished: {}", counts);
        return counts;
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String pageJson) {
        try {
            return objectMapper.readTree(pageJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unparseable page in Notion query response", ex);
        }
    }

    private static void record(Map<SyncOutcome, Integer> counts, SyncOutcome outcome) {
        counts.merge(outcome, 1, Integer::sum);
    }
}
