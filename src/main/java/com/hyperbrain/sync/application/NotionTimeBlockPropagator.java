package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.NotionApiException;
import com.hyperbrain.sync.domain.model.TimeBlockChangedEvent;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import com.hyperbrain.sync.domain.port.out.PlannerTimeBlockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Notion {@link IEventPropagator} for the Time Blocks mirror (ADR-038): the fourth Notion-bound
 * aggregate, deliberately kept out of {@link NotionEventPropagator} as its own delegate so the
 * executable/cycle propagator does not grow a fourth branch. Two event families reach it:
 *
 * <ul>
 *   <li><b>{@code AGENDA_BLOCK}</b> ({@code AgendaBlockPlannedEvent}, the planner's day-scoped
 *       delivery event, same source {@code AgendaBlockPropagator} consumes for Apple): re-reads
 *       the day's live {@code PLANNER}/{@code USER} blocks and mirrors each one — a replan
 *       therefore converges by <b>updating</b> the surviving pages (stable identity, ADR-027 D3,
 *       regression #15) — and archives the pages of {@code removed_block_ids}.</li>
 *   <li><b>{@code CORE_TIME_BLOCK}</b> ({@code TimeBlockSettledEvent} from the settlement paths
 *       and the fine-grained {@code TimeBlockChangedEvent}): mirrors that single block. The
 *       framework loop protection already suppresses NOTION-origin events; SYSTEM re-assertions
 *       carry the visible {@code Sync Note} in the payload (ADR-020 marker pattern).</li>
 * </ul>
 *
 * <p>Runs on a virtual thread outside the drain transaction; a persistent Notion failure marks
 * the mapping {@code ERROR} and rethrows so the outbox event is retried (all operations are
 * idempotent). An archived page is <em>not</em> a failure — see
 * {@link NotionTimeBlockMirrorService}.
 */
@Service
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionTimeBlockPropagator implements IEventPropagator {

    private static final Logger log = LoggerFactory.getLogger(NotionTimeBlockPropagator.class);

    private static final String AGENDA_BLOCK_AGGREGATE = "AGENDA_BLOCK";
    private static final String TIME_BLOCK_AGGREGATE = TimeBlockChangedEvent.AGGREGATE_TYPE;
    private static final String PLANNED_EVENT = "AgendaBlockPlannedEvent";
    private static final String SETTLED_EVENT = "TimeBlockSettledEvent";
    private static final String CHANGED_EVENT = TimeBlockChangedEvent.EVENT_TYPE;

    private final PlannerTimeBlockPort blockPort;
    private final NotionTimeBlockMirrorService mirrorService;
    private final ObjectMapper objectMapper;

    public NotionTimeBlockPropagator(
        PlannerTimeBlockPort blockPort,
        NotionTimeBlockMirrorService mirrorService,
        ObjectMapper objectMapper
    ) {
        this.blockPort = blockPort;
        this.mirrorService = mirrorService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExternalSystem target() {
        return ExternalSystem.NOTION;
    }

    @Override
    public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
        return origin != ExternalSystem.UNKNOWN
            && (entityType == SyncedEntityType.AGENDA_BLOCK
                || entityType == SyncedEntityType.TIME_BLOCK);
    }

    @Override
    public void propagate(OutboxEvent event) {
        try {
            if (AGENDA_BLOCK_AGGREGATE.equals(event.aggregateType())) {
                if (PLANNED_EVENT.equals(event.eventType())) {
                    propagatePlannedDay(event);
                }
                return;
            }
            if (TIME_BLOCK_AGGREGATE.equals(event.aggregateType())) {
                propagateSingleBlock(event);
            }
        } catch (NotionApiException ex) {
            UUID blockId = parseUuid(event.aggregateId());
            if (blockId != null) {
                mirrorService.markError(blockId);
            }
            log.error("Notion time-block mirror failed for event {}; left unprocessed for retry",
                event.id(), ex);
            throw ex;
        }
    }

    /** Mirrors the whole (re)planned day: upserts the surviving blocks, archives the removed. */
    private void propagatePlannedDay(OutboxEvent event) {
        JsonNode payload = parsePayload(event.payload());
        if (payload == null) {
            log.warn("AgendaBlockPlannedEvent {} has unparseable payload; Notion mirror skipped",
                event.id());
            return;
        }
        UUID userId = parseUuid(payload.path("user_id").asText(null));
        LocalDate targetDay = parseDate(payload.path("target_day").asText(null));
        ZoneId zone = parseZone(payload.path("zone_id").asText(null));
        if (userId == null || targetDay == null || zone == null) {
            log.warn("AgendaBlockPlannedEvent {} has incomplete coordinates; Notion mirror skipped",
                event.id());
            return;
        }
        OffsetDateTime dayStart = targetDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDay.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<TimeBlockSnapshot> blocks = blockPort.findLiveBlocksForDay(userId, dayStart, dayEnd);
        for (TimeBlockSnapshot block : blocks) {
            mirrorService.mirror(block, null, false);
        }
        List<UUID> removedBlockIds = parseUuidArray(payload.path("removed_block_ids"));
        for (UUID removedBlockId : removedBlockIds) {
            mirrorService.archiveAndUnmap(removedBlockId);
        }
        log.info("Notion mirror: {} block(s) upserted, {} removal(s) archived for user {} on {} "
            + "(event {})", blocks.size(), removedBlockIds.size(), userId, targetDay, event.id());
    }

    /** Mirrors one block from a settlement or a fine-grained change event. */
    private void propagateSingleBlock(OutboxEvent event) {
        UUID blockId = parseUuid(event.aggregateId());
        if (blockId == null) {
            log.warn("CORE_TIME_BLOCK event {} carries no usable block id; skipped", event.id());
            return;
        }
        String operation = null;
        String syncNote = null;
        if (CHANGED_EVENT.equals(event.eventType())) {
            JsonNode payload = parsePayload(event.payload());
            operation = payload != null ? payload.path("operation").asText(null) : null;
            syncNote = payload != null ? payload.path("sync_note").asText(null) : null;
        } else if (!SETTLED_EVENT.equals(event.eventType())) {
            return;
        }
        if ("DELETED".equals(operation)) {
            mirrorService.archiveAndUnmap(blockId);
            return;
        }
        Optional<TimeBlockSnapshot> block = blockPort.findBlock(blockId);
        if (block.isEmpty()) {
            // The row vanished between the event and the drain (e.g. a de-scheduling won the
            // race): converge by clearing the page instead of mirroring a stale snapshot.
            mirrorService.archiveAndUnmap(blockId);
            return;
        }
        mirrorService.mirror(block.get(), syncNote, false);
    }

    private JsonNode parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static List<UUID> parseUuidArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            UUID id = parseUuid(element.asText(null));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private static ZoneId parseZone(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZoneId.of(value);
        } catch (java.time.DateTimeException ex) {
            return null;
        }
    }
}
