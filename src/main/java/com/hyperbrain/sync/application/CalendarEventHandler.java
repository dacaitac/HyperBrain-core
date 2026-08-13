package com.hyperbrain.sync.application;

import com.hyperbrain.core.domain.model.ReleaseCause;
import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.core.domain.port.in.ExecutableContainmentService;
import com.hyperbrain.prioritizer.application.OnIngestionPriorityReflector;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.SyncSnapshotRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.service.SourceAwareMerge;
import com.hyperbrain.sync.infrastructure.PayloadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles inbound {@link EntityType#CALENDAR_EVENT} sync events from Apple EventKit.
 *
 * <p>Same pipeline as {@link ReminderEventHandler} (HU-09 + ADR-012): parse → checksum →
 * source-aware merge (Apple authority: name, notes, start/end, calendar; status and type are
 * kept — EventKit events carry no completed flag and {@code AGENDA} stays {@code AGENDA}) →
 * {@link DomainChangeProcessor} → single persist → post-upsert priority reflection via
 * {@link OnIngestionPriorityReflector} (#66a, ADR-020 D2) → Outbox. For this APPLE origin the
 * reflector stages no SYSTEM event; the APPLE event carries the fresh score to Notion (see
 * {@link ReminderEventHandler}).
 *
 * <p><b>An unmapped event is not necessarily a new one.</b> Before creating a row, the handler asks
 * whether the event is our own write coming back under an identifier EventKit reassigned when the
 * event moved between accounts — see {@link #adoptEcho}. Without that question an agenda entry the
 * user creates is duplicated as an activity, and the mapping we hold is left pointing at an event
 * that no longer exists.
 */
@Component
public class CalendarEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventHandler.class);

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
    private final ExecutableContainmentService containment;
    private final WriteCommandLogRepository commandLogRepo;
    private final UUID defaultUserId;
    private final Duration echoWindow;

    public CalendarEventHandler(
        CoreExecutableRepository executableRepo,
        SyncSnapshotRepository snapshotRepo,
        SyncMappingRepository syncMappingRepo,
        OutboxRepository outboxRepo,
        DomainChangeProcessor domainChangeProcessor,
        OnIngestionPriorityReflector priorityReflector,
        PayloadParser payloadParser,
        ExecutableContainmentService containment,
        WriteCommandLogRepository commandLogRepo,
        @Value("${app.sync.default-user-id}") UUID defaultUserId,
        @Value("${app.sync.apple.echo-window:PT10M}") Duration echoWindow
    ) {
        this.executableRepo = executableRepo;
        this.snapshotRepo = snapshotRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.outboxRepo = outboxRepo;
        this.domainChangeProcessor = domainChangeProcessor;
        this.priorityReflector = priorityReflector;
        this.payloadParser = payloadParser;
        this.containment = containment;
        this.commandLogRepo = commandLogRepo;
        this.defaultUserId = defaultUserId;
        this.echoWindow = echoWindow;
    }

    @Override
    public EntityType supportedType() {
        return EntityType.CALENDAR_EVENT;
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
        CalendarEventPayload payload = payloadParser.parseCalendarEvent(event.payload());
        String checksum = ChecksumCalculator.compute(
            event.entityId(), event.operation().name(), event.payload());

        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isPresent() && checksum.equals(existing.get().lastKnownChecksum())) {
            log.debug("CALENDAR_EVENT {} unchanged (checksum match), discarding event {}",
                event.entityId(), event.eventId());
            return;
        }

        // An unmapped event may still be one of ours under a new identifier (see adoptEcho).
        Optional<UUID> adopted = existing.isEmpty() ? adoptEcho(event, payload) : Optional.empty();

        UUID executableId = existing.map(SyncMapping::localId)
            .or(() -> adopted)
            .orElseGet(UUID::randomUUID);
        ExecutableSnapshot current = existing.isPresent() || adopted.isPresent()
            ? snapshotRepo.findExecutable(executableId).orElse(null)
            : null;
        ExecutableSnapshot merged =
            SourceAwareMerge.mergeCalendarEvent(current, executableId, defaultUserId, payload);
        ExecutableSnapshot processed =
            domainChangeProcessor.process(current, merged, ExternalSystem.APPLE);
        executableRepo.upsert(processed);
        // Score the persisted merged row (ADR-020, D2). For an APPLE origin the reflector stages no
        // extra event: the CalendarEventSyncedEvent below already carries the fresh score to Notion.
        priorityReflector.reflect(executableId, ExternalSystem.APPLE);

        if (existing.isEmpty()) {
            // An adopted echo carries the dead mapping of the identifier EventKit discarded. It goes
            // first: leaving it would give the executable two APPLE mappings, and the write-back reads
            // one of them — the dead one half the time, which is the state this whole path repairs.
            adopted.ifPresent(this::dropStaleMapping);
            syncMappingRepo.insert(buildSyncMapping(executableId, event.entityId(), checksum));
        } else {
            syncMappingRepo.update(buildSyncMapping(executableId, event.entityId(), checksum));
        }

        outboxRepo.append(buildOutboxEvent(event, executableId, "CalendarEventSyncedEvent"));
        log.info("CALENDAR_EVENT {} ({}) persisted as executable {}",
            event.entityId(), event.operation(), executableId);
    }

    /**
     * Recognises an unmapped inbound event as one of <em>our own</em> writes, returning the executable
     * it belongs to so the ingestion updates that row instead of creating a second one.
     *
     * <p><b>Why an event of ours arrives unrecognisable.</b> EventKit cannot move an event between
     * accounts: re-targeting one from a calendar on one source to a calendar on another deletes it and
     * creates it again, with a <b>new identifier</b>. That is what a type change does here — an
     * executable that becomes {@code AGENDA} mid-ingestion re-homes its event from the iCloud calendar
     * to the Google one — and it is not hypothetical: the burst of Notion webhooks that follows creating
     * a page walks the row through more than one type, so the event is written, then moved. What comes
     * back is a {@code CREATED} for an identifier nobody has ever seen, while the mapping we hold points
     * at an event that no longer exists. Left alone the ingestion does the only thing it can with an
     * unknown event: it creates a row — typed {@code ACTIVITY}, because that is what an unknown calendar
     * event is — and the user sees their agenda entry duplicated as an activity.
     *
     * <p><b>Identity by content, and only just after a write.</b> The write log is the only place that
     * remembers the event was ours; the payload is the only thing that survives the reassignment. The
     * match is exact on title and window and bounded to the echo window, so an event the user creates by
     * hand is adopted only if it is identical to something we wrote moments ago — in which case treating
     * the two as one is the right answer anyway. Ambiguity is refused, never guessed.
     *
     * @return the executable to update, or empty when this really is a new event
     */
    private Optional<UUID> adoptEcho(SentinelEvent event, CalendarEventPayload payload) {
        Optional<UUID> owner = commandLogRepo.findCalendarWriteByContent(
            payload.title(), payload.startTime(), payload.endTime(),
            OffsetDateTime.now().minus(echoWindow));
        owner.ifPresent(localId -> log.warn(
            "CALENDAR_EVENT {} is our own write of executable {} under a reassigned identifier "
                + "(event moved between accounts); adopting it instead of creating a duplicate",
            event.entityId(), localId));
        return owner;
    }

    /** Removes the executable's APPLE mapping to the identifier EventKit discarded, if it still holds one. */
    private void dropStaleMapping(UUID localId) {
        syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId)
            .ifPresent(stale -> {
                syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, stale.externalId());
                log.info("Dropped dead APPLE mapping {} of executable {}",
                    stale.externalId(), localId);
            });
    }

    /**
     * Applies an inbound calendar-event delete. Since ADR-039 the mapping's {@code local_id} is always
     * a {@code core_executable} — an activity, an agenda entry, or a block, which is now an executable
     * like any other — so there is a single delete path and no second one to route to.
     *
     * <p>An inbound delete is always propagated, regardless of the mapping's age. An earlier version
     * skipped very recent mappings on the theory that an EKEvent's {@code eventIdentifier} could mutate
     * shortly after creation (a spurious {@code DELETED(oldId) + CREATED(newId)} pair). Empirical
     * validation on the Mac Mini disproved it: a manual deletion of 23 "HyperBrain" calendar blocks
     * produced 23 genuine DELETEs and zero re-mapping CREATEs — the ids do not mutate in this setup, so
     * the guard only suppressed real user deletions and left the DB pointing at dead events.
     */
    private void handleDeleted(SentinelEvent event) {
        Optional<SyncMapping> existing = syncMappingRepo.findByExternalSystemAndId(
            EXTERNAL_SYSTEM, event.entityId());

        if (existing.isEmpty()) {
            log.warn("CALENDAR_EVENT {} not found in sync_mappings; DELETE event {} has no effect",
                event.entityId(), event.eventId());
            return;
        }

        UUID localId = existing.get().localId();

        if (executableRepo.findById(localId).isEmpty()) {
            log.warn("CALENDAR_EVENT {} DELETE maps to local {}, which no longer exists; no-op",
                event.entityId(), localId);
            return;
        }

        // Let anything the row was holding go BEFORE deleting it (ADR-040 D10). Relying on the
        // database to detach members on cascade mutates rows without passing through the domain and
        // without emitting a single event, so the calendar and Notion keep the hour of a block that no
        // longer exists — silent corruption of the mirrors that never self-corrects because nobody
        // finds out. A user's gesture releases them WITH their hour intact: you deleted a block, you
        // did not ask to lose the time you had set aside for what was in it.
        containment.releaseMembers(localId, ReleaseCause.USER_DETACH, userZone());
        executableRepo.deleteById(localId);
        syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, event.entityId());
        outboxRepo.append(buildOutboxEvent(event, localId, "CalendarEventDeletedEvent"));
        log.info("CALENDAR_EVENT {} deleted (executable {})", event.entityId(), localId);
    }

    /**
     * The zone the released members' day is reasoned in. A user detach keeps each member's hour, so the
     * zone never actually decides anything on this path — it is required by the operation's signature
     * because the planner's withdrawal, the other caller, does demote the hour to midnight.
     */
    private java.time.ZoneId userZone() {
        return java.time.ZoneOffset.UTC;
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
