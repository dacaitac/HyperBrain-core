package com.hyperbrain.planner.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.AgendaBlockPlannedEvent;
import com.hyperbrain.planner.domain.model.EmptyAgendaProposedEvent;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import com.hyperbrain.sync.infrastructure.WriteCommandWireMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Apple {@link IEventPropagator} for the morning agenda write-back (HU-01b delivery slice): turns an
 * {@link AgendaBlockPlannedEvent} drained from the Transactional Outbox into one
 * {@link WriteCommand} per {@code PLANNED} block on {@code apple-commands.fifo}, materializing each
 * block as a {@code CALENDAR_EVENT} (Daniel, 2026-07-10) — a time-boxed EKEvent that keeps the
 * block's start and end, unlike a reminder — whose notes carry the block's placement reason plus the
 * day's readable energy criterion (Sleep Score → margin → quota).
 *
 * <p><b>Deliberate exception to the system-generated suppression.</b> Planner blocks are
 * {@code system_generated} accounting rows, and {@code AppleEventPropagator} suppresses those (they
 * are internal). This propagator is the sanctioned way around that rule (HU-01b): it reacts to the
 * distinct {@code AGENDA_BLOCK} aggregate — never to executable events — and re-reads the blocks
 * directly, so a block reaches Apple as a calendar event without ever passing through the executable
 * suppression guard, and a plain executable change never reaches this propagator.
 *
 * <p><b>Destination calendar.</b> The event targets HyperBrain's own writable calendar
 * ({@code calendar_name = "HyperBrain"}, {@code calendar_id} left empty for SentinelAPI to resolve),
 * never a read-only AGENDA calendar (ADR-009) — those are sync-only and must not be written to.
 *
 * <p><b>Block identity and reconciliation.</b> Each block is mapped in {@code sync_mappings} under its
 * own {@code core_time_block.id} as {@code local_id}, kept separate from the executable's own mapping
 * so the block event and the task it schedules never collide. Because a regeneration preserves that id
 * (#15, {@code PlannerBlockIdentity}), reusing the HU-09c command log + results loop: a surviving
 * mapped block <b>updates</b> its EventKit event, a genuinely new block <b>creates</b> one, and a block
 * the regeneration dropped is carried in {@code removed_block_ids} and <b>deleted</b> from Apple — so a
 * replan reconciles the day's events instead of duplicating them. The {@code WriteCommandResult} closes
 * (create) or removes (delete) the mapping, ADR-010.
 *
 * <p><b>Idempotency.</b> The {@code commandId} is derived deterministically from the outbox event id
 * and the block id, so a retried drain re-emits the same commands (SQS FIFO + SentinelAPI dedup
 * absorb the duplicate) rather than creating duplicate events. Runs on a virtual thread outside the
 * drain transaction; any failure leaves the outbox row unprocessed for retry (at-least-once).
 */
@Service
public class AgendaBlockPropagator implements IEventPropagator {

    private static final Logger log = LoggerFactory.getLogger(AgendaBlockPropagator.class);

    private static final String EXTERNAL_SYSTEM = "APPLE";
    private static final String COMMAND_ID_NAMESPACE = "hyperbrain-agenda-block:";
    private static final String STATUS_PENDING = "PENDING";
    /** HyperBrain's own writable calendar — never a read-only AGENDA calendar (ADR-009). */
    private static final String DESTINATION_CALENDAR_NAME = "HyperBrain";

    private final PlannerStateRepository plannerStateRepository;
    private final SyncMappingRepository syncMappingRepo;
    private final WriteCommandLogRepository commandLogRepo;
    private final WriteCommandPublisher commandPublisher;
    private final WriteCommandWireMapper wireMapper;
    private final EmptyAgendaNotifier emptyAgendaNotifier;
    private final ObjectMapper objectMapper;

    public AgendaBlockPropagator(
        PlannerStateRepository plannerStateRepository,
        SyncMappingRepository syncMappingRepo,
        WriteCommandLogRepository commandLogRepo,
        WriteCommandPublisher commandPublisher,
        WriteCommandWireMapper wireMapper,
        EmptyAgendaNotifier emptyAgendaNotifier,
        ObjectMapper objectMapper
    ) {
        this.plannerStateRepository = plannerStateRepository;
        this.syncMappingRepo = syncMappingRepo;
        this.commandLogRepo = commandLogRepo;
        this.commandPublisher = commandPublisher;
        this.wireMapper = wireMapper;
        this.emptyAgendaNotifier = emptyAgendaNotifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExternalSystem target() {
        return ExternalSystem.APPLE;
    }

    @Override
    public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
        return origin != ExternalSystem.UNKNOWN && entityType == SyncedEntityType.AGENDA_BLOCK;
    }

    @Override
    public void propagate(OutboxEvent event) {
        if (!AgendaBlockPlannedEvent.AGGREGATE_TYPE.equals(event.aggregateType())) {
            return;
        }
        if (EmptyAgendaProposedEvent.EVENT_TYPE.equals(event.eventType())) {
            propagateEmptyDayProposal(event);
            return;
        }
        JsonNode payload = parsePayload(event.payload());
        if (payload == null) {
            log.warn("AgendaBlockPlannedEvent {} has unparseable payload; skipping write-back", event.id());
            return;
        }
        UUID userId = parseUuid(payload.path("user_id").asText(null));
        LocalDate targetDay = parseDate(payload.path("target_day").asText(null));
        ZoneId zone = parseZone(payload.path("zone_id").asText(null));
        String energyCriterion = payload.path("energy_criterion").asText("");
        if (userId == null || targetDay == null || zone == null) {
            log.warn("AgendaBlockPlannedEvent {} has incomplete coordinates; skipping write-back", event.id());
            return;
        }

        List<PlannedBlockRecord> blocks =
            plannerStateRepository.loadPlannedBlocksForDay(userId, targetDay, zone);
        List<UUID> removedBlockIds = parseUuidArray(payload.path("removed_block_ids"));
        if (blocks.isEmpty() && removedBlockIds.isEmpty()) {
            log.info("AgendaBlockPlannedEvent {}: nothing to deliver for user {} on {}",
                event.id(), userId, targetDay);
            return;
        }

        for (PlannedBlockRecord block : blocks) {
            emitBlock(event, userId, block, energyCriterion);
        }
        for (UUID removedBlockId : removedBlockIds) {
            emitRemoval(event, userId, removedBlockId);
        }
        log.info("Delivered {} agenda block(s) and {} removal(s) as calendar events for user {} on {} (event {})",
            blocks.size(), removedBlockIds.size(), userId, targetDay, event.id());
    }

    /**
     * Emits the empty-day next-day proposal (HU-01c H2 negative case). The notice was staged in the
     * Transactional Outbox atomically with the materialization claim; here — post-commit, off the drain
     * transaction — it is turned into the reminder. {@link EmptyAgendaNotifier} derives the command id
     * deterministically from {@code (user, day)}, so an at-least-once drain re-emits the same command
     * (SQS FIFO + SentinelAPI dedup absorb it) rather than doubling the notice.
     */
    private void propagateEmptyDayProposal(OutboxEvent event) {
        JsonNode payload = parsePayload(event.payload());
        if (payload == null) {
            log.warn("EmptyAgendaProposedEvent {} has unparseable payload; skipping notice", event.id());
            return;
        }
        UUID userId = parseUuid(payload.path("user_id").asText(null));
        LocalDate targetDay = parseDate(payload.path("target_day").asText(null));
        String energyCriterion = payload.path("energy_criterion").asText("");
        OffsetDateTime referenceInstant = parseTimestamp(payload.path("reference_instant").asText(null));
        if (userId == null || targetDay == null || referenceInstant == null) {
            log.warn("EmptyAgendaProposedEvent {} has incomplete coordinates; skipping notice", event.id());
            return;
        }
        emptyAgendaNotifier.proposeNextDay(userId, targetDay, energyCriterion, referenceInstant);
    }

    private void emitBlock(OutboxEvent event, UUID userId, PlannedBlockRecord block,
                           String energyCriterion) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, block.blockId());
        Operation operation = mapping.isPresent() ? Operation.UPDATED : Operation.CREATED;
        String entityId = mapping.map(SyncMapping::externalId).orElse(null);
        String groupKey = mapping.isPresent() ? entityId : block.blockId().toString();
        UUID commandId = deterministicCommandId(event.id(), block.blockId());

        // A time-boxed calendar event (start + end), not a reminder: the block's duration must not be
        // lost. Written to HyperBrain's own writable calendar, never a read-only AGENDA one (ADR-009);
        // all_day is left false — SentinelAPI derives it at the Apple boundary. calendar_id is empty
        // so SentinelAPI resolves the target by name.
        CalendarEventPayload payload = new CalendarEventPayload(
            block.executableName(),
            block.start(),
            block.end(),
            false,
            eventNotes(block.reason(), energyCriterion),
            "",
            DESTINATION_CALENDAR_NAME,
            null);
        WriteCommand command =
            new WriteCommand(commandId, CommandType.CALENDAR_EVENT, operation, entityId, payload);

        commandLogRepo.upsertPending(new PendingWriteCommand(
            commandId, userId, block.blockId(), CommandType.CALENDAR_EVENT, operation, entityId,
            wireMapper.payloadJson(payload), STATUS_PENDING));
        commandPublisher.publish(command, groupKey);
        log.debug("Agenda block {} ({}-{}) emitted as calendar event command {} ({}) for user {}",
            block.blockId(), block.start(), block.end(), commandId, operation, userId);
    }

    /**
     * Emits the {@code DELETE} of a block that a regeneration dropped from the plan (#15): its
     * {@code core_time_block} row is already gone, but its {@code sync_mapping} survives (no cascade),
     * so its EKEvent id is resolved here and a {@code CALENDAR_EVENT DELETED} command is published,
     * keyed by the EKEvent id so SentinelAPI applies it after any prior update of the same entity. The
     * {@code sync_mapping} itself is closed by the write-command result loop once Apple confirms the
     * delete (ADR-010), mirroring the executable delete path — never here. A block with no mapping was
     * never delivered to Apple, so its removal is a no-op.
     */
    private void emitRemoval(OutboxEvent event, UUID userId, UUID blockId) {
        Optional<SyncMapping> mapping =
            syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, blockId);
        if (mapping.isEmpty()) {
            log.debug("Removed agenda block {} has no Apple mapping; nothing to delete", blockId);
            return;
        }
        String externalId = mapping.get().externalId();
        UUID commandId = deterministicCommandId(event.id(), blockId);
        WriteCommand command = new WriteCommand(
            commandId, CommandType.CALENDAR_EVENT, Operation.DELETED, externalId, null);

        commandLogRepo.upsertPending(new PendingWriteCommand(
            commandId, userId, blockId, CommandType.CALENDAR_EVENT, Operation.DELETED, externalId,
            null, STATUS_PENDING));
        commandPublisher.publish(command, externalId);
        log.info("Removed agenda block {}: deleting Apple event {} (command {}) for user {}",
            blockId, externalId, commandId, userId);
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

    /**
     * Builds the event notes: the block's placement reason followed by the day's readable energy
     * criterion, so every block explains why it is there and how the day's load was sized
     * (legibilidad obligatoria).
     */
    private static String eventNotes(String reason, String energyCriterion) {
        StringBuilder body = new StringBuilder();
        if (reason != null && !reason.isBlank()) {
            body.append(reason.trim());
        }
        if (!energyCriterion.isBlank()) {
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(energyCriterion.trim());
        }
        return body.length() == 0 ? null : body.toString();
    }

    private JsonNode parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
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

    private static OffsetDateTime parseTimestamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
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

    private static UUID deterministicCommandId(UUID outboxEventId, UUID blockId) {
        return UUID.nameUUIDFromBytes(
            (COMMAND_ID_NAMESPACE + outboxEventId + ":" + blockId).getBytes(StandardCharsets.UTF_8));
    }
}
