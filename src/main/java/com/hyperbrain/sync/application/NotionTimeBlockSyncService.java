package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.NotionTimeBlockPage;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.TimeBlockChangedEvent;
import com.hyperbrain.sync.domain.model.TimeBlockEditOutcome;
import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockUserEdit;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.service.NotionTimeBlockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Inbound half of the SyncServ for the Notion Time Blocks database (ADR-038): applies one page
 * state to the planner-owned {@code core_time_block} aggregate through
 * {@link PlannerTimeBlockPort} — the {@code sync} module never mutates the block itself.
 *
 * <p><b>Authority matrix</b> (ADR-012 D1 amendment, row TIME_BLOCK): USER-writable from Notion,
 * on <em>live</em> blocks only — the {@code Date} window, the theme (extracted from the composed
 * title) and the membership set ({@code Tasks} relation). Everything else ({@code Status},
 * {@code Origin}, minutes, {@code Agenda}, {@code Reason}) is SYSTEM-owned and follows
 * ignore-and-reassert. {@code SETTLED}/{@code EXPIRED} blocks are frozen history: any edit is
 * rejected and the canonical state re-asserted with a visible {@code Sync Note}.
 *
 * <p><b>Effective diff, loss-aware</b>: the current PG state is projected to its Notion
 * representation and compared with what the page carries; an identical projection is an absolute
 * no-op — the origin is <em>never</em> flipped on an echo (the anti-echo guard: without it the
 * re-mirror's own echo would flip PLANNER blocks en masse and freeze the daily reconciliation).
 * A genuine diff is applied through the port and flips {@code origin=USER}, the wall the replan
 * respects.
 *
 * <p><b>Everything the page must visibly converge on travels through the Transactional
 * Outbox</b> (never a Notion write inside the ingestion transaction): an applied or rejected
 * edit stages a SYSTEM {@code CANONICAL_STATE} re-assertion (composed title, derived Agenda,
 * {@code Origin=User}, cleared or explanatory {@code Sync Note}); membership changes stage one
 * {@code ExecutableUpdatedEvent} per added <em>and removed</em> member (the ADR-035 D-C due-date
 * re-projection hook); D5 move side effects stage the source block's re-mirror or removal.
 * Rejections are permanent semantic conflicts — never an ERROR marker, never the DLQ.
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTimeBlockSyncService {

    private static final Logger log = LoggerFactory.getLogger(NotionTimeBlockSyncService.class);

    private static final String EXTERNAL_SYSTEM = "NOTION";
    private static final String SOURCE_SYSTEM_LOCAL = "SYSTEM";
    private static final String STATUS_SYNCED = "SYNCED";
    private static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";
    private static final ZoneId NOTION_ZONE = ZoneId.of("America/Bogota");
    private static final String NOTE_SEPARATOR = " · ";

    private final PlannerTimeBlockPort blockPort;
    private final SyncMappingRepository syncMappingRepo;
    private final NotionTimeBlockMirrorService mirrorService;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final UUID defaultUserId;

    public NotionTimeBlockSyncService(
        PlannerTimeBlockPort blockPort,
        SyncMappingRepository syncMappingRepo,
        NotionTimeBlockMirrorService mirrorService,
        OutboxRepository outboxRepo,
        ObjectMapper objectMapper,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.blockPort = blockPort;
        this.syncMappingRepo = syncMappingRepo;
        this.mirrorService = mirrorService;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
        this.defaultUserId = defaultUserId;
    }

    /**
     * Applies one Time Blocks page state to the domain.
     *
     * @param page the parsed page
     * @return what was done with the state
     */
    public SyncOutcome apply(NotionTimeBlockPage page) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, page.pageId());
        if (page.archived()) {
            return handleArchived(page.pageId(), mapping);
        }
        if (mapping.isEmpty()) {
            return createFromNotion(page);
        }
        if (isStale(page.lastEditedTime(), mapping.get())) {
            log.info("TIME_BLOCK page {} is stale (last_edited_time < last_synced_at); "
                + "discarded (CA-29)", page.pageId());
            return SyncOutcome.SKIPPED_STALE;
        }
        UUID blockId = mapping.get().localId();
        Optional<TimeBlockSnapshot> current = blockPort.findBlock(blockId);
        if (current.isEmpty()) {
            // The local row is gone but the page lingers: converge by removing the satellite
            // counterparts (the propagators archive the page and delete the mapping).
            appendChanged(TimeBlockChangedEvent.of(blockId, mapping.get().userId(),
                Operation.DELETED, SOURCE_SYSTEM_LOCAL, OffsetDateTime.now()));
            log.warn("TIME_BLOCK page {} maps to vanished block {}; removal staged",
                page.pageId(), blockId);
            return SyncOutcome.DELETED;
        }
        TimeBlockSnapshot block = current.get();

        // The USER-writable diff decides first (core#57 hotfix): a checksum-first echo discard
        // is wrong for this aggregate, because the stored checksum covers the canonical
        // projection of the CURRENT ROW — after any mirror write, every later inbound delivery
        // (including a genuine user retime) reproduces it and would be swallowed as an echo (the
        // ghost-state worst case). The checksum only breaks the tie once the page carries no
        // user-facet change: bit-identical to our last write ⇒ true echo; otherwise ⇒
        // SYSTEM-owned drift to re-assert.
        EffectiveDiff diff = computeDiff(block, page);
        if (block.terminal()) {
            if (diff.touchesUserFacets()) {
                // Frozen history is read-only, hard: reject + re-assert with a visible note.
                appendChanged(TimeBlockChangedEvent.reassertion(blockId, block.userId(),
                    OffsetDateTime.now(), "Settled blocks are frozen history; edit reverted"));
                log.info("TIME_BLOCK page {}: rejected — terminal block {} is read-only; "
                    + "canonical state re-asserted", page.pageId(), blockId);
                return SyncOutcome.REASSERTED;
            }
            if (isEcho(mapping.get(), block, page)) {
                log.info("TIME_BLOCK page {}: no-op — echo of the mirror's own write on terminal "
                    + "block {} (CA-4)", page.pageId(), blockId);
                return SyncOutcome.SKIPPED_ECHO;
            }
            appendChanged(TimeBlockChangedEvent.reassertion(blockId, block.userId(),
                OffsetDateTime.now(), null));
            log.info("TIME_BLOCK page {}: SYSTEM-owned drift on terminal block {}; canonical "
                + "state re-asserted", page.pageId(), blockId);
            return SyncOutcome.REASSERTED;
        }
        if (!diff.touchesUserFacets()) {
            if (isEcho(mapping.get(), block, page)) {
                // A true echo is an absolute no-op — crucially the origin is never flipped.
                log.info("TIME_BLOCK page {}: no-op — echo of the mirror's own write on block {} "
                    + "(CA-4)", page.pageId(), blockId);
                return SyncOutcome.SKIPPED_ECHO;
            }
            // Only SYSTEM-owned fields drifted (Status/Origin/minutes/Agenda/Reason edits):
            // ignore-and-reassert, and still never flip the origin.
            appendChanged(TimeBlockChangedEvent.reassertion(blockId, block.userId(),
                OffsetDateTime.now(), null));
            log.info("TIME_BLOCK page {}: SYSTEM-owned drift on block {}; canonical re-asserted",
                page.pageId(), blockId);
            return SyncOutcome.REASSERTED;
        }

        TimeBlockUserEdit edit = new TimeBlockUserEdit(blockId, diff.newStart(), diff.newEnd(),
            diff.newTheme(), diff.desiredMembers());
        TimeBlockEditOutcome outcome = blockPort.applyUserEdit(edit);
        stageSideEffects(block.userId(), outcome);
        if (outcome.applied()) {
            // The Apple counterpart must follow the accepted edit; NOTION origin keeps the
            // Notion propagator out of the loop (RF-17) — the page itself converges through the
            // SYSTEM re-assertion below (composed title, derived Agenda, Origin=User).
            appendChanged(TimeBlockChangedEvent.of(blockId, block.userId(), Operation.UPDATED,
                EXTERNAL_SYSTEM, OffsetDateTime.now()));
        }
        String note = buildNote(outcome.rejections(), diff.notes());
        appendChanged(TimeBlockChangedEvent.reassertion(blockId, block.userId(),
            OffsetDateTime.now(), note));
        log.info("TIME_BLOCK page {}: {} on block {} — retime={}, retheme={}, membership={}, "
                + "{} rejection(s){}", page.pageId(),
            outcome.applied() ? "applied (origin=USER)" : "rejected", blockId,
            diff.newStart() != null, diff.newTheme() != null, diff.desiredMembers() != null,
            outcome.rejections().size(),
            note != null ? " — Sync Note: " + note : "");
        return outcome.applied() ? SyncOutcome.UPDATED : SyncOutcome.REASSERTED;
    }

    /**
     * Processes a page that is archived, trashed or no longer accessible.
     *
     * @param pageId normalized Notion page id
     * @return the outcome
     */
    public SyncOutcome handleArchived(String pageId) {
        return handleArchived(pageId,
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, pageId));
    }

    private SyncOutcome handleArchived(String pageId, Optional<SyncMapping> mapping) {
        if (mapping.isEmpty()) {
            log.info("TIME_BLOCK page {}: no-op — archived page has no mapping", pageId);
            return SyncOutcome.DELETED;
        }
        UUID blockId = mapping.get().localId();
        Optional<TimeBlockSnapshot> block = blockPort.findBlock(blockId);
        if (block.isPresent() && block.get().live()) {
            // Archiving the page of a live block de-schedules it (ADR-038 rule 7): delete the
            // block and clean the satellites — the Apple echo rides the NOTION-origin event.
            blockPort.deleteLiveBlock(blockId);
            appendChanged(TimeBlockChangedEvent.of(blockId, mapping.get().userId(),
                Operation.DELETED, EXTERNAL_SYSTEM, OffsetDateTime.now()));
            log.info("TIME_BLOCK page {} archived: live block {} de-scheduled", pageId, blockId);
        } else {
            // Archiving a terminal block is presentation-only: PG history is never touched, the
            // mapping goes so nothing ever resurrects the page (anti-resurrection).
            log.info("TIME_BLOCK page {} archived for terminal/absent block {}; mapping removed",
                pageId, blockId);
        }
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, pageId);
        return SyncOutcome.DELETED;
    }

    /**
     * A page born in Notion (ADR-038 rule 8): {@code origin=USER}, {@code status=PLANNED},
     * relation-only — members must already exist as mapped Tasks pages, executables are never
     * created from here (anti-second-inbox). A page without a parseable {@code Date.start} or
     * without any mapped member stays a Notion-side draft: skipped with a log, no mapping.
     */
    private SyncOutcome createFromNotion(NotionTimeBlockPage page) {
        OffsetDateTime start = parseNotionInstant(page.dateStart());
        if (start == null) {
            log.info("TIME_BLOCK page {} has no usable Date.start; left as a Notion draft",
                page.pageId());
            return SyncOutcome.SKIPPED_ECHO;
        }
        if (page.taskRelationHasMore()) {
            log.warn("TIME_BLOCK page {} carries a truncated Tasks relation; creation deferred",
                page.pageId());
            return SyncOutcome.SKIPPED_ECHO;
        }
        List<UUID> members = mapMembers(page.taskRelationIds());
        if (members.isEmpty()) {
            log.info("TIME_BLOCK page {} references no mapped task; left as a Notion draft",
                page.pageId());
            return SyncOutcome.SKIPPED_ECHO;
        }
        OffsetDateTime end = parseNotionInstant(page.dateEnd());
        if (end == null || !end.isAfter(start)) {
            end = start.plusHours(1);
        }
        String theme = NotionTimeBlockMapper.extractTheme(page.title());
        UUID blockId = UUID.randomUUID();
        TimeBlockEditOutcome outcome =
            blockPort.createUserBlock(defaultUserId, blockId, start, end, theme, members);
        stageSideEffects(defaultUserId, outcome);
        if (!outcome.applied()) {
            log.info("TIME_BLOCK page {} not materialized ({}); left as a Notion draft",
                page.pageId(), String.join("; ", outcome.rejections()));
            return SyncOutcome.REASSERTED;
        }
        TimeBlockSnapshot created = blockPort.findBlock(blockId).orElseThrow();
        Map<String, Object> canonical = mirrorService.canonicalProps(created, null);
        OffsetDateTime syncedAt = page.lastEditedTime() != null
            ? page.lastEditedTime()
            : OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        syncMappingRepo.insert(new SyncMapping(UUID.randomUUID(), defaultUserId, blockId,
            EXTERNAL_SYSTEM, page.pageId(), mirrorService.checksum(page.pageId(), canonical),
            STATUS_SYNCED, syncedAt));
        appendChanged(TimeBlockChangedEvent.of(blockId, defaultUserId, Operation.CREATED,
            EXTERNAL_SYSTEM, OffsetDateTime.now()));
        appendChanged(TimeBlockChangedEvent.reassertion(blockId, defaultUserId,
            OffsetDateTime.now(), buildNote(outcome.rejections(), List.of())));
        log.info("TIME_BLOCK page {} materialized as USER/PLANNED block {}", page.pageId(), blockId);
        return SyncOutcome.CREATED;
    }

    /**
     * True echo detector (CA-4), only consulted once the delivery carries no USER-facet change:
     * the canonical projection of the current row with the note the page shows must reproduce
     * the stored checksum bit-identically — i.e. the page is exactly what the mirror last wrote.
     */
    private boolean isEcho(SyncMapping mapping, TimeBlockSnapshot block, NotionTimeBlockPage page) {
        Map<String, Object> canonical = mirrorService.canonicalProps(block, page.syncNote());
        return ChecksumSupport.matches(mapping.lastKnownChecksum(), page.pageId(), canonical,
            objectMapper);
    }

    // ── Effective diff (loss-aware projection) ────────────────────────────────

    private record EffectiveDiff(OffsetDateTime newStart, OffsetDateTime newEnd, String newTheme,
                                 Set<UUID> desiredMembers, List<String> notes) {

        boolean touchesUserFacets() {
            return newStart != null || newTheme != null || desiredMembers != null;
        }
    }

    /**
     * Projects the PG state to its Notion representation and keeps only the USER-writable
     * facets the page genuinely moved. Loss-aware: comparisons run at the representation's own
     * precision (minutes), so a projection round-trip never manufactures a diff.
     */
    private EffectiveDiff computeDiff(TimeBlockSnapshot block, NotionTimeBlockPage page) {
        List<String> notes = new ArrayList<>();

        // Window facet.
        OffsetDateTime newStart = null;
        OffsetDateTime newEnd = null;
        OffsetDateTime pageStart = parseNotionInstant(page.dateStart());
        if (pageStart == null) {
            // Date is required on a mirrored block; clearing it is not interpretable.
            notes.add("Date is required; window restored");
        } else {
            OffsetDateTime pageEnd = parseNotionInstant(page.dateEnd());
            if (pageEnd == null || !pageEnd.isAfter(pageStart)) {
                // A dropped end bound moves the block but keeps its duration (loss-aware).
                long minutes = block.dateEnd() != null
                    ? Duration.between(block.dateStart(), block.dateEnd()).toMinutes()
                    : 60;
                pageEnd = pageStart.plusMinutes(Math.max(minutes, 1));
            }
            boolean sameStart = sameMinute(pageStart, block.dateStart());
            boolean sameEnd = block.dateEnd() != null && sameMinute(pageEnd, block.dateEnd());
            if (!sameStart || !sameEnd) {
                newStart = pageStart;
                newEnd = pageEnd;
            }
        }

        // Theme facet — extracted from the composed title.
        String newTheme = null;
        String pageTheme = NotionTimeBlockMapper.extractTheme(page.title());
        if (pageTheme != null && !pageTheme.equals(block.themeOrAnchorName())) {
            newTheme = pageTheme;
        }

        // Membership facet — block-side reading only, guarded against truncation (has_more/25).
        Set<UUID> desiredMembers = null;
        if (page.taskRelationHasMore()) {
            notes.add("Tasks relation truncated by Notion; membership not applied");
        } else {
            List<UUID> mapped = mapMembers(page.taskRelationIds());
            if (mapped.size() < page.taskRelationIds().size()) {
                log.warn("TIME_BLOCK page {}: {} unmapped task relation(s); membership left "
                        + "unchanged this round", page.pageId(),
                    page.taskRelationIds().size() - mapped.size());
            } else {
                Set<UUID> desired = new LinkedHashSet<>(mapped);
                Set<UUID> currentMembers = new LinkedHashSet<>();
                for (TimeBlockMemberSnapshot member : block.members()) {
                    currentMembers.add(member.executableId());
                }
                if (!desired.equals(currentMembers)) {
                    desiredMembers = desired;
                }
            }
        }
        return new EffectiveDiff(newStart, newEnd, newTheme, desiredMembers, notes);
    }

    // ── Side effects ──────────────────────────────────────────────────────────

    /**
     * Stages the outbox consequences of a port outcome: the ADR-035 D-C due-date re-projection
     * per added and removed member, the re-mirror of D5-move source blocks, and the removal of
     * blocks a move emptied.
     */
    private void stageSideEffects(UUID userId, TimeBlockEditOutcome outcome) {
        for (UUID executableId : outcome.addedMembers()) {
            appendMemberRefresh(executableId);
        }
        for (UUID executableId : outcome.removedMembers()) {
            appendMemberRefresh(executableId);
        }
        for (UUID remirrorId : outcome.remirrorBlockIds()) {
            appendChanged(TimeBlockChangedEvent.of(remirrorId, userId, Operation.UPDATED,
                SOURCE_SYSTEM_LOCAL, OffsetDateTime.now()));
        }
        for (UUID deletedId : outcome.deletedBlockIds()) {
            appendChanged(TimeBlockChangedEvent.of(deletedId, userId, Operation.DELETED,
                SOURCE_SYSTEM_LOCAL, OffsetDateTime.now()));
        }
    }

    /**
     * The ADR-035 D-C hook: an {@code ExecutableUpdatedEvent} per touched member lets the
     * satellite mirrors re-project the member's schedule-derived state (due date) on additions
     * <em>and</em> removals alike.
     */
    private void appendMemberRefresh(UUID executableId) {
        String payload = String.format("{\"local_id\":\"%s\",\"operation\":\"UPDATED\"}",
            executableId);
        outboxRepo.append(new OutboxEvent(UUID.randomUUID(), EXECUTABLE_AGGREGATE,
            executableId.toString(), "ExecutableUpdatedEvent", payload, SOURCE_SYSTEM_LOCAL,
            OffsetDateTime.now()));
    }

    private void appendChanged(TimeBlockChangedEvent event) {
        outboxRepo.append(new OutboxEvent(UUID.randomUUID(),
            TimeBlockChangedEvent.AGGREGATE_TYPE, event.blockId().toString(),
            TimeBlockChangedEvent.EVENT_TYPE, toJson(event), event.sourceSystem(),
            event.occurredAt()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<UUID> mapMembers(List<String> taskPageIds) {
        List<UUID> members = new ArrayList<>(taskPageIds.size());
        for (String taskPageId : taskPageIds) {
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, taskPageId)
                .map(SyncMapping::localId)
                .ifPresent(members::add);
        }
        return members;
    }

    private static String buildNote(List<String> rejections, List<String> diffNotes) {
        List<String> parts = new ArrayList<>(rejections.size() + diffNotes.size());
        parts.addAll(rejections);
        parts.addAll(diffNotes);
        return parts.isEmpty() ? null : String.join(NOTE_SEPARATOR, parts);
    }

    /** Parses a Notion date bound: full timestamps as-is, date-only values at local midnight. */
    private static OffsetDateTime parseNotionInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ex) {
            try {
                return LocalDate.parse(raw).atStartOfDay(NOTION_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static boolean sameMinute(OffsetDateTime a, OffsetDateTime b) {
        return a.truncatedTo(ChronoUnit.MINUTES).isEqual(b.truncatedTo(ChronoUnit.MINUTES));
    }

    /** CA-29 monotonicity guard, strictly-older (same-minute edits fall through to CA-4). */
    private static boolean isStale(OffsetDateTime lastEditedTime, SyncMapping mapping) {
        return lastEditedTime != null
            && mapping.lastSyncedAt() != null
            && lastEditedTime.isBefore(mapping.lastSyncedAt());
    }

    private String toJson(TimeBlockChangedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("TimeBlockChangedEvent payload serialization failed", ex);
        }
    }
}
