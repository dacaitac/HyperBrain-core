package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionPageEditState;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.domain.service.NotionCycleMapper;
import com.hyperbrain.sync.domain.service.NotionSchema;
import com.hyperbrain.sync.domain.service.NotionTaskMapper;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import com.hyperbrain.sync.infrastructure.NotionSyncProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Notion {@link IEventPropagator} (HU-10, refactored to the propagator pattern in HU-14 CA-11):
 * propagates {@code core_executable} changes to the Tasks database and {@code core_cycle}
 * changes to the Cycles database (ADR-011: Cycles sync is fully bidirectional).
 *
 * <p>Routing rules (RF-17 loop protection):
 * <ul>
 *   <li>Framework contract: the drain never invokes it for {@code source_system = NOTION};
 *       {@link #shouldPropagate} additionally rejects unknown origins. {@code APPLE} and
 *       {@code SYSTEM} do propagate (CA-2), for executables and cycles alike.
 *   <li>Unlike the Apple write-back (ADR-009), {@code AGENDA} executables ARE propagated —
 *       the read-only restriction applies only towards Apple.
 * </ul>
 *
 * <p>Runs on a virtual thread outside the drain transaction (HU-14 CA-13): every statement
 * commits independently, so the {@code sync_status=ERROR} marker survives the rethrow that
 * leaves the outbox event unprocessed for retry; partial writes are repaired by the retry
 * (all operations are idempotent). A failure never cancels the sibling propagators (CA-23).
 *
 * <p>Contract with Notion (ADR-011, no result queue): CREATE persists the synchronously
 * returned {@code page_id} in {@code sync_mappings} within the same drain transaction (CA-3);
 * UPDATE patches by {@code external_id} (CA-4); DELETE archives the page and removes the
 * mapping (CA-5). After every write {@code last_known_checksum} and {@code last_synced_at}
 * are refreshed so the HU-14 webhook echo is discarded by checksum (CA-7); the checksum
 * recipe is {@code SHA-256(external_id + operation + propertiesJson)}.
 *
 * <p>Failure handling (CA-13/CA-15): a page deleted manually in Notion ({@code 404}) repairs
 * the mapping — recreation on upsert, removal on delete. A persistent API failure marks the
 * mapping {@code sync_status=ERROR} (visible in Appsmith), increments the failure counter
 * (Grafana alerting, HU-11) and rethrows, leaving the outbox event unprocessed for retry.
 *
 * <p>Concurrency with in-flight human edits (ADR-020, burst protection): the write-back is not a
 * blind full-page mirror any more.
 * <ul>
 *   <li><b>Priority reflections</b> (SYSTEM tick / on-ingestion, marked {@code
 *       reflection=PRIORITY_SCORE}) PATCH only the SYSTEM-owned score properties and skip the
 *       pre-read — the user never edits scores, so they cannot collide with a manual edit.
 *   <li><b>Genuine domain changes</b> (USER/APPLE and non-priority SYSTEM rules) still mirror the
 *       full page, but first run the outbound staleness guard ({@link #hasHumanEditInFlight}): if a
 *       person is editing the page in the same minute, the write-back is <em>discarded</em> (not
 *       retried, no ERROR marker) and the inbound webhook reconciles via {@code SourceAwareMerge}.
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionEventPropagator implements IEventPropagator {

    private static final Logger log = LoggerFactory.getLogger(NotionEventPropagator.class);

    private static final String EXTERNAL_SYSTEM = "NOTION";
    private static final String STATUS_SYNCED = "SYNCED";
    private static final String STATUS_ERROR = "ERROR";

    private static final Set<String> EXECUTABLE_AGGREGATES = Set.of("CORE_EXECUTABLE", "TASK");
    private static final String CYCLE_AGGREGATE = "CORE_CYCLE";

    /**
     * Inbound Apple sync events (HU-09 handlers) also describe {@code core_executable} changes,
     * but carry the EventKit id in {@code aggregate_id}; the local UUID travels in the payload
     * as {@code local_id}.
     */
    private static final String SYNC_APPLE_AGGREGATE = "SYNC_APPLE";

    private static final Map<String, Operation> EXECUTABLE_EVENT_OPERATIONS = Map.of(
        "ExecutableCreatedEvent", Operation.CREATED,
        "ExecutableUpdatedEvent", Operation.UPDATED,
        "TaskCompletedEvent", Operation.UPDATED,
        "ExecutableDeletedEvent", Operation.DELETED);

    /**
     * Event types that reflect a change onto an executable that already exists and whose write-back
     * must therefore be update-only: if the target has no {@code sync_mapping} yet, the entity was
     * never mirrored here and the reflection is skipped rather than creating a spurious page.
     *
     * <p>This closes the score-reflection routes (#66a): the scheduled tick
     * ({@code PriorityReflectionService}) and the on-event recalculation ride {@code
     * ExecutableUpdatedEvent}, which fired mass creates for executables that legitimately have no
     * Notion Tasks page (e.g. {@code ACTIVITY} calendar events). Across the codebase these two event
     * types are only ever emitted for pre-existing entities, so update-only is always correct here;
     * the genuine create paths use {@code ExecutableCreatedEvent} and the Apple {@code *SyncedEvent}s,
     * which are unaffected.
     */
    private static final Set<String> UPDATE_ONLY_EXECUTABLE_EVENTS =
        Set.of("ExecutableUpdatedEvent", "TaskCompletedEvent");

    /**
     * Payload marker (staged by {@code OnIngestionPriorityReflector} and {@code
     * PriorityReflectionService}) identifying a write-back that only re-asserts the SYSTEM-owned
     * scores. Such a reflection PATCHes only {@link #PRIORITY_REFLECTION_PROPS} instead of mirroring
     * the whole page, and skips the outbound human-edit pre-read (ADR-020): the user never edits
     * scores, so a manual-edit burst can never collide with it, which also saves a Notion read.
     */
    private static final String PRIORITY_REFLECTION_MARKER = "PRIORITY_SCORE";

    /**
     * The SYSTEM-owned Notion properties a priority reflection is allowed to touch. Restricted to
     * the scores the Prioritizer computes; every other property stays exactly as the user left it.
     * ({@code priority_computed_at} is a Core-internal recompute timestamp and is not mirrored to
     * Notion — it has no Tasks-database property.)
     */
    private static final Set<String> PRIORITY_REFLECTION_PROPS =
        Set.of(NotionSchema.PROP_PRIORITY_SCORE, NotionSchema.PROP_URGENCE);

    private static final Map<String, Operation> SYNC_APPLE_EVENT_OPERATIONS = Map.of(
        "ReminderSyncedEvent", Operation.UPDATED,
        "CalendarEventSyncedEvent", Operation.UPDATED,
        "ReminderDeletedEvent", Operation.DELETED,
        "CalendarEventDeletedEvent", Operation.DELETED);

    private static final Map<String, Operation> CYCLE_EVENT_OPERATIONS = Map.of(
        "CycleCreatedEvent", Operation.CREATED,
        "CycleUpdatedEvent", Operation.UPDATED,
        "CycleCompletedEvent", Operation.UPDATED,
        "CycleDeletedEvent", Operation.DELETED);

    private final SyncSnapshotRepository snapshotRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final NotionPort notion;
    private final NotionPageParser pageParser;
    private final NotionSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public NotionEventPropagator(
        SyncSnapshotRepository snapshotRepo,
        SyncMappingRepository syncMappingRepo,
        NotionPort notion,
        NotionPageParser pageParser,
        NotionSyncProperties properties,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.snapshotRepo = snapshotRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.notion = notion;
        this.pageParser = pageParser;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ExternalSystem target() {
        return ExternalSystem.NOTION;
    }

    @Override
    public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
        return origin != ExternalSystem.UNKNOWN
            && (entityType == SyncedEntityType.EXECUTABLE || entityType == SyncedEntityType.CYCLE);
    }

    @Override
    public void propagate(OutboxEvent event) {
        boolean isExecutable = EXECUTABLE_AGGREGATES.contains(event.aggregateType());
        boolean isAppleSync = SYNC_APPLE_AGGREGATE.equals(event.aggregateType());
        boolean isCycle = CYCLE_AGGREGATE.equals(event.aggregateType());
        if (!isExecutable && !isAppleSync && !isCycle) {
            return;
        }
        Operation operation;
        if (isExecutable) {
            operation = EXECUTABLE_EVENT_OPERATIONS.get(event.eventType());
        } else if (isAppleSync) {
            operation = SYNC_APPLE_EVENT_OPERATIONS.get(event.eventType());
        } else {
            operation = CYCLE_EVENT_OPERATIONS.get(event.eventType());
        }
        if (operation == null) {
            return;
        }
        UUID localId = isAppleSync
            ? extractPayloadLocalId(event)
            : parseLocalId(event.aggregateId());
        if (localId == null) {
            log.warn("Outbox event {} carries no usable local id (aggregate_id '{}'); "
                + "skipping Notion write-back", event.id(), event.aggregateId());
            return;
        }

        boolean updateOnly = isExecutable && UPDATE_ONLY_EXECUTABLE_EVENTS.contains(event.eventType());
        boolean priorityReflection = isExecutable && isPriorityReflection(event);
        try {
            if (operation == Operation.DELETED) {
                propagateDelete(localId);
            } else if (isCycle) {
                propagateCycleUpsert(event, localId);
            } else {
                propagateExecutableUpsert(event, localId, updateOnly, priorityReflection);
            }
        } catch (NotionApiException ex) {
            onPersistentFailure(localId, ex);
            throw ex;
        }
    }

    /**
     * Whether a drained executable event is a SYSTEM priority reflection (tick or on-ingestion,
     * ADR-020) rather than a genuine domain change. Only these are field-scoped to the score
     * properties; every other SYSTEM write-back (focus, progress, settlement) stays a full mirror.
     */
    private boolean isPriorityReflection(OutboxEvent event) {
        if (ExternalSystem.from(event.sourceSystem()) != ExternalSystem.SYSTEM
            || event.payload() == null) {
            return false;
        }
        try {
            return PRIORITY_REFLECTION_MARKER.equals(
                objectMapper.readTree(event.payload()).path("reflection").asText(null));
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    /** Reads the {@code local_id} field the HU-09 handlers embed in the outbox payload. */
    private UUID extractPayloadLocalId(OutboxEvent event) {
        if (event.payload() == null) {
            return null;
        }
        try {
            String localId = objectMapper.readTree(event.payload()).path("local_id").asText(null);
            return localId != null ? parseLocalId(localId) : null;
        } catch (JsonProcessingException ex) {
            log.warn("Outbox event {} has unparseable payload; skipping Notion write-back",
                event.id(), ex);
            return null;
        }
    }

    private void propagateExecutableUpsert(OutboxEvent event, UUID localId, boolean updateOnly,
                                           boolean priorityReflection) {
        Optional<ExecutableSnapshot> snapshot = snapshotRepo.findExecutable(localId);
        if (snapshot.isEmpty()) {
            log.warn("Executable {} not found for outbox event {}; skipping Notion write-back",
                localId, event.id());
            return;
        }
        if (snapshot.get().systemGenerated()) {
            log.debug("System-generated executable {}; internal accounting, not written back",
                localId);
            return;
        }
        if (updateOnly && !isMapped(localId)) {
            log.debug("Executable {} has no Notion mapping; update-only reflection skips create "
                + "for event {}", localId, event.id());
            return;
        }
        String cycleExternalId = resolveCycleRelation(snapshot.get().cycleId());
        String parentExternalId = resolveMappedExternalId(snapshot.get().parentId());
        Map<String, Object> props =
            NotionTaskMapper.map(snapshot.get(), cycleExternalId, parentExternalId);
        Set<String> patchScope = priorityReflection ? PRIORITY_REFLECTION_PROPS : null;
        upsertPage(localId, snapshot.get().userId(), properties.getTasksDataSourceId(),
            props, patchScope);
    }

    /** Returns whether the executable already has a Notion page (a {@code sync_mapping} row). */
    private boolean isMapped(UUID localId) {
        return syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId).isPresent();
    }

    private void propagateCycleUpsert(OutboxEvent event, UUID localId) {
        Optional<CycleSnapshot> snapshot = snapshotRepo.findCycle(localId);
        if (snapshot.isEmpty()) {
            log.warn("Cycle {} not found for outbox event {}; skipping Notion write-back",
                localId, event.id());
            return;
        }
        Map<String, Object> props = NotionCycleMapper.map(snapshot.get());
        upsertPage(localId, snapshot.get().userId(), properties.getCyclesDataSourceId(), props, null);
    }

    /**
     * Creates or patches the Notion page for one local entity and refreshes its
     * {@code sync_mapping} (checksum + {@code last_synced_at}, CA-3/CA-4/CA-7).
     *
     * <p>{@code patchScope} splits the two write-back kinds (ADR-020):
     * <ul>
     *   <li><b>null (full mirror)</b> — a genuine domain change (USER/APPLE, or a non-priority SYSTEM
     *       rule). Touches human-editable fields, so before patching it runs the outbound staleness
     *       guard ({@link #hasHumanEditInFlight}) and discards the write-back when a person is editing
     *       the page, letting the inbound webhook reconcile.
     *   <li><b>a property subset (score reflection)</b> — patches only those SYSTEM-owned properties
     *       and skips the pre-read: the user never edits scores, so it cannot collide.
     * </ul>
     *
     * <p>The stored checksum is always computed over the <em>full</em> canonical {@code props}, even
     * for a scoped patch, so it stays consistent with the inbound echo check (CA-4), which recomputes
     * the full property map from the row — a scope-narrowed checksum would make every score echo miss
     * and bounce back as a spurious update (RF-17 loop).
     */
    private void upsertPage(UUID localId, UUID userId, String dataSourceId,
                            Map<String, Object> props, Set<String> patchScope) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId);
        if (mapping.isEmpty()) {
            createPage(localId, userId, dataSourceId, props);
            return;
        }
        String externalId = mapping.get().externalId();
        if (patchScope == null && hasHumanEditInFlight(externalId, mapping.get())) {
            log.info("Notion page {} has a human edit in flight; write-back for entity {} discarded "
                + "(SKIPPED_HUMAN_EDIT), inbound webhook reconciles", externalId, localId);
            recordSkip();
            return;
        }
        Map<String, Object> patch = patchScope == null ? props : scopedProps(props, patchScope);
        try {
            notion.updatePage(externalId, patch);
        } catch (NotionPageNotFoundException ex) {
            log.warn("Notion page {} for entity {} vanished; recreating mapping (CA-15)",
                externalId, localId);
            syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, externalId);
            createPage(localId, userId, dataSourceId, props);
            return;
        }
        String checksum = checksum(externalId, Operation.UPDATED, props);
        syncMappingRepo.update(new SyncMapping(
            mapping.get().id(), mapping.get().userId(), localId, EXTERNAL_SYSTEM,
            externalId, checksum, STATUS_SYNCED, notionClockNow()));
        recordWrite(Operation.UPDATED);
        log.info("Notion page {} updated for entity {}{}", externalId, localId,
            patchScope != null ? " (score reflection, field-scoped)" : "");
    }

    /**
     * Outbound counterpart of the CA-29 monotonicity guard: decides whether a full-mirror write-back
     * would clobber a human edit still in flight. Re-reads the page and yields to a <em>person</em>'s
     * edit — the reliable signal being {@code last_edited_by} (Notion truncates {@code
     * last_edited_time} to the minute, exactly the width of an edit burst, so the timestamp alone
     * gives false negatives). It only re-reads for genuine domain changes; score reflections skip
     * this path, saving a Notion read.
     *
     * <p>Bias, opposite to CA-29 (which uses strictly-older to keep same-minute human edits inbound):
     * this discards on <em>equal-or-newer</em>, so a same-minute human edit wins over the write-back
     * rather than being overwritten. The actor gate is what makes the equal-minute case decidable —
     * the Core's own just-written page also carries the current minute, but as {@code last_edited_by}
     * = the integration bot, so it never blocks the Core's own follow-up writes.
     *
     * <p>Inert when the integration bot id is not configured (the actor cannot be identified): the
     * guard returns false and the write-back proceeds as before.
     */
    private boolean hasHumanEditInFlight(String externalId, SyncMapping mapping) {
        String botUserId = NotionPageParser.normalizeId(properties.getBotUserId());
        if (botUserId == null || botUserId.isBlank()) {
            return false;
        }
        NotionPageEditState edit;
        try {
            edit = pageParser.parseEditState(objectMapper.readTree(notion.retrievePage(externalId)));
        } catch (NotionPageNotFoundException ex) {
            return false; // gone: let the update path 404 and repair the mapping (CA-15)
        } catch (JsonProcessingException ex) {
            log.warn("Unparseable Notion page {} on the outbound guard; proceeding with write-back",
                externalId);
            return false;
        }
        boolean editedByBot = edit.lastEditedById() == null
            || edit.lastEditedById().equals(botUserId);
        if (editedByBot) {
            return false; // the last touch was the Core's own write — no human edit in flight
        }
        OffsetDateTime lastSyncedAt = mapping.lastSyncedAt();
        OffsetDateTime lastEdited = edit.lastEditedTime();
        // Person edited: discard unless their edit is strictly older than our last sync (already
        // reconciled). Missing timestamps fall back to the actor signal alone (discard, to be safe).
        return lastSyncedAt == null || lastEdited == null || !lastEdited.isBefore(lastSyncedAt);
    }

    private static Map<String, Object> scopedProps(Map<String, Object> fullProps, Set<String> scope) {
        Map<String, Object> scoped = new LinkedHashMap<>();
        for (String key : scope) {
            Object value = fullProps.get(key);
            if (value != null) {
                scoped.put(key, value);
            }
        }
        return scoped;
    }

    private void createPage(UUID localId, UUID userId, String dataSourceId,
                            Map<String, Object> props) {
        String externalId = normalizePageId(notion.createPage(dataSourceId, props));
        String checksum = checksum(externalId, Operation.CREATED, props);
        syncMappingRepo.insert(new SyncMapping(
            UUID.randomUUID(), userId, localId, EXTERNAL_SYSTEM,
            externalId, checksum, STATUS_SYNCED, notionClockNow()));
        recordWrite(Operation.CREATED);
        log.info("Notion page {} created for entity {}", externalId, localId);
    }

    private void propagateDelete(UUID localId) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId);
        if (mapping.isEmpty()) {
            log.debug("Entity {} has no Notion mapping; DELETE needs no write-back", localId);
            return;
        }
        String externalId = mapping.get().externalId();
        try {
            notion.archivePage(externalId);
        } catch (NotionPageNotFoundException ex) {
            log.info("Notion page {} already gone; removing stale mapping (CA-15)", externalId);
        }
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, externalId);
        recordWrite(Operation.DELETED);
        log.info("Notion page {} archived for deleted entity {}", externalId, localId);
    }

    /**
     * Resolves the Notion page id of the owning cycle; when the cycle is not mapped yet its
     * page is created first, so the task's {@code Cycle} relation always resolves (CA-6).
     */
    private String resolveCycleRelation(UUID cycleId) {
        if (cycleId == null) {
            return null;
        }
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, cycleId);
        if (mapping.isPresent()) {
            return mapping.get().externalId();
        }
        Optional<CycleSnapshot> cycle = snapshotRepo.findCycle(cycleId);
        if (cycle.isEmpty()) {
            log.warn("Cycle {} referenced by an executable no longer exists; relation omitted",
                cycleId);
            return null;
        }
        Map<String, Object> props = NotionCycleMapper.map(cycle.get());
        String externalId = normalizePageId(
            notion.createPage(properties.getCyclesDataSourceId(), props));
        syncMappingRepo.insert(new SyncMapping(
            UUID.randomUUID(), cycle.get().userId(), cycleId, EXTERNAL_SYSTEM,
            externalId, checksum(externalId, Operation.CREATED, props), STATUS_SYNCED,
            notionClockNow()));
        recordWrite(Operation.CREATED);
        log.info("Notion cycle page {} created on demand for cycle {} (CA-6)", externalId, cycleId);
        return externalId;
    }

    /** Returns the mapped Notion page id, or null when the entity has no mapping yet. */
    private String resolveMappedExternalId(UUID localId) {
        if (localId == null) {
            return null;
        }
        return syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId)
            .map(SyncMapping::externalId)
            .orElse(null);
    }

    private void onPersistentFailure(UUID localId, NotionApiException ex) {
        meterRegistry.counter("hyperbrain.sync.notion.failures").increment();
        syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId)
            .ifPresent(mapping -> syncMappingRepo.update(new SyncMapping(
                mapping.id(), mapping.userId(), mapping.localId(), EXTERNAL_SYSTEM,
                mapping.externalId(), mapping.lastKnownChecksum(), STATUS_ERROR,
                mapping.lastSyncedAt())));
        log.error("Notion write-back failed for entity {}; outbox event left unprocessed for retry",
            localId, ex);
    }

    private void recordWrite(Operation operation) {
        meterRegistry.counter("hyperbrain.sync.notion.writes",
            "operation", operation.name().toLowerCase(Locale.ROOT)).increment();
    }

    private void recordSkip() {
        meterRegistry.counter("hyperbrain.sync.notion.skips",
            "reason", "human_edit_in_flight").increment();
    }

    private String checksum(String externalId, Operation operation, Map<String, Object> props) {
        return ChecksumCalculator.compute(externalId, operation.name(), propertiesJson(props));
    }

    private String propertiesJson(Map<String, Object> props) {
        try {
            return objectMapper.writeValueAsString(props);
        } catch (JsonProcessingException ex) {
            // Property maps only contain strings, numbers and booleans; this never happens.
            throw new IllegalStateException("Unserializable Notion property map", ex);
        }
    }

    private static String normalizePageId(String pageId) {
        return pageId.replace("-", "");
    }

    /**
     * {@code last_synced_at} stored after an outbound write, truncated to the minute because
     * Notion truncates {@code last_edited_time} the same way: the CA-29 guard compares the two,
     * and an untruncated wall clock would out-date (and silently drop) user edits made in the
     * same minute as the write-back.
     */
    private static OffsetDateTime notionClockNow() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    }

    private static UUID parseLocalId(String aggregateId) {
        try {
            return UUID.fromString(aggregateId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
