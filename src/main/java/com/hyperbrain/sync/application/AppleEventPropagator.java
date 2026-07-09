package com.hyperbrain.sync.application;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import com.hyperbrain.sync.domain.service.WriteCommandFactory;
import com.hyperbrain.sync.infrastructure.WriteCommandWireMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Apple {@link IEventPropagator} (HU-09c, refactored to the propagator pattern in HU-14 CA-12):
 * turns {@code core_executable} changes drained from the Transactional Outbox into
 * {@link WriteCommand}s on {@code apple-commands.fifo}.
 *
 * <p>Routing rules (RF-17 loop protection + ADR-009):
 * <ul>
 *   <li>Framework contract: the drain never invokes it for {@code source_system = APPLE};
 *       {@link #shouldPropagate} additionally rejects unknown origins and non-executable
 *       aggregates (cycles have no Apple counterpart).
 *   <li>Executables mapped as {@code AGENDA} are read-only and never produce a command; that
 *       check needs the persisted row, so it lives in {@link #propagate} (ADR-009).
 *   <li>The effective operation follows the {@code sync_mapping}: mapped → UPDATED against the
 *       EventKit id; unmapped → CREATED with a null id, closed later by the
 *       {@code WriteCommandResult} (ADR-010).
 * </ul>
 *
 * <p>The {@code commandId} is derived deterministically from the outbox event id, so a retried
 * drain re-emits the same command (SQS FIFO + SentinelAPI dedup absorb the duplicate) instead of
 * creating a second EventKit entity. Runs on a virtual thread outside the drain transaction
 * (CA-13): every statement commits independently and retries repair any partial write (all
 * operations are idempotent). Any failure leaves the outbox row unprocessed for retry and
 * never cancels the sibling propagators of the same event (CA-23).
 */
@Service
public class AppleEventPropagator implements IEventPropagator {

    private static final Logger log = LoggerFactory.getLogger(AppleEventPropagator.class);

    private static final String EXTERNAL_SYSTEM = "APPLE";
    private static final String COMMAND_ID_NAMESPACE = "hyperbrain-write-command:";
    private static final String STATUS_PENDING = "PENDING";

    /** Outbox aggregate types that represent a {@code core_executable} change. */
    private static final Set<String> EXECUTABLE_AGGREGATES = Set.of("CORE_EXECUTABLE", "TASK");

    private static final Map<String, Operation> EVENT_OPERATIONS = Map.of(
        "ExecutableCreatedEvent", Operation.CREATED,
        "ExecutableUpdatedEvent", Operation.UPDATED,
        "TaskCompletedEvent", Operation.UPDATED,
        "ExecutableDeletedEvent", Operation.DELETED);

    private final CoreExecutableRepository executableRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final WriteCommandLogRepository commandLogRepo;
    private final WriteCommandPublisher commandPublisher;
    private final WriteCommandWireMapper wireMapper;

    public AppleEventPropagator(
        CoreExecutableRepository executableRepo,
        SyncMappingRepository syncMappingRepo,
        WriteCommandLogRepository commandLogRepo,
        WriteCommandPublisher commandPublisher,
        WriteCommandWireMapper wireMapper
    ) {
        this.executableRepo = executableRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.commandLogRepo = commandLogRepo;
        this.commandPublisher = commandPublisher;
        this.wireMapper = wireMapper;
    }

    @Override
    public ExternalSystem target() {
        return ExternalSystem.APPLE;
    }

    @Override
    public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
        return origin != ExternalSystem.UNKNOWN && entityType == SyncedEntityType.EXECUTABLE;
    }

    @Override
    public void propagate(OutboxEvent event) {
        if (!EXECUTABLE_AGGREGATES.contains(event.aggregateType())) {
            return;
        }
        Operation operation = EVENT_OPERATIONS.get(event.eventType());
        if (operation == null) {
            return;
        }
        UUID localId = parseLocalId(event.aggregateId());
        if (localId == null) {
            log.warn("Outbox event {} has non-UUID aggregate_id '{}'; skipping Apple write-back",
                event.id(), event.aggregateId());
            return;
        }

        if (operation == Operation.DELETED) {
            propagateDelete(event, localId);
        } else {
            propagateUpsert(event, localId);
        }
    }

    private void propagateUpsert(OutboxEvent event, UUID localId) {
        Optional<CoreExecutable> executable = executableRepo.findById(localId);
        if (executable.isEmpty()) {
            log.warn("Executable {} not found for outbox event {}; skipping Apple write-back",
                localId, event.id());
            return;
        }
        if (!WriteCommandFactory.isWritable(executable.get().type())) {
            log.info("Executable {} has type {} (read-only or unsupported for Apple, ADR-009); "
                + "no WriteCommand emitted", localId, executable.get().type());
            return;
        }

        Optional<SyncMapping> mapping = syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId);
        UUID userId = executable.get().userId();

        if (mapping.isEmpty()) {
            UUID commandId = deterministicCommandId(event.id());
            Optional<PendingWriteCommand> inFlight = commandLogRepo.findPendingCreateByLocalId(localId);
            if (inFlight.isPresent() && !inFlight.get().commandId().equals(commandId)) {
                log.warn("CREATE already in flight for executable {} (command {}); skipping event {}",
                    localId, inFlight.get().commandId(), event.id());
                return;
            }
            emit(WriteCommandFactory.forUpsert(commandId, executable.get(), Operation.CREATED, null),
                event, userId, localId, localId.toString());
            return;
        }

        CommandType desiredKind =
            WriteCommandFactory.commandTypeForExecutableType(executable.get().type()).orElseThrow();
        Optional<CommandType> mappedKind = commandLogRepo.findLastWrittenCommandTypeByLocalId(localId);
        if (mappedKind.isPresent() && mappedKind.get() != desiredKind) {
            propagateKindTransition(event, executable.get(), userId, localId, mapping.get(),
                mappedKind.get(), desiredKind);
            return;
        }

        String externalId = mapping.get().externalId();
        emit(WriteCommandFactory.forUpsert(deterministicCommandId(event.id()), executable.get(),
                Operation.UPDATED, externalId),
            event, userId, localId, externalId);
    }

    /**
     * Handles an executable whose type crossed the reminder↔event boundary (e.g. TASK → ACTIVITY):
     * the mapped Apple entity is the wrong kind, so it is deleted and a fresh entity of the new
     * kind is created. The two commands carry distinct deterministic ids (idempotent under drain
     * retries) and are grouped by {@code localId} so SentinelAPI applies them in order. The mapping
     * transition is race-free: the DELETE result clears the old row (keyed by its {@code external_id})
     * and the CREATE result inserts the new one, in any order (ADR-010).
     */
    private void propagateKindTransition(OutboxEvent event, CoreExecutable executable, UUID userId,
                                         UUID localId, SyncMapping mapping,
                                         CommandType oldKind, CommandType newKind) {
        WriteCommand deleteCommand = WriteCommandFactory.forDelete(
            deterministicCommandId(event.id(), "delete"), oldKind, mapping.externalId());
        emit(Optional.of(deleteCommand), event, userId, localId, mapping.externalId());

        emit(WriteCommandFactory.forUpsert(deterministicCommandId(event.id(), "create"),
                executable, Operation.CREATED, null),
            event, userId, localId, localId.toString());
        log.info("Executable {} changed Apple kind {} -> {}; deleted entity {} and re-created",
            localId, oldKind, newKind, mapping.externalId());
    }

    private void propagateDelete(OutboxEvent event, UUID localId) {
        Optional<SyncMapping> mapping = syncMappingRepo.findByExternalSystemAndLocalId(EXTERNAL_SYSTEM, localId);
        if (mapping.isEmpty()) {
            log.debug("Executable {} has no Apple mapping; DELETE event {} needs no write-back",
                localId, event.id());
            return;
        }
        CommandType commandType = wireMapper.extractCommandType(event.payload())
            .orElse(CommandType.REMINDER);
        String externalId = mapping.get().externalId();
        WriteCommand command = WriteCommandFactory.forDelete(
            deterministicCommandId(event.id()), commandType, externalId);
        emit(Optional.of(command), event, mapping.get().userId(), localId, externalId);
    }

    private void emit(Optional<WriteCommand> maybeCommand, OutboxEvent event, UUID userId, UUID localId,
                      String groupKey) {
        if (maybeCommand.isEmpty()) {
            log.warn("Executable {} not representable as an Apple payload; event {} skipped",
                localId, event.id());
            return;
        }
        WriteCommand command = maybeCommand.get();
        String payloadJson = command.payload() != null ? wireMapper.payloadJson(command.payload()) : null;

        commandLogRepo.upsertPending(new PendingWriteCommand(
            command.commandId(), userId, localId, command.commandType(),
            command.operation(), command.entityId(), payloadJson, STATUS_PENDING));
        commandPublisher.publish(command, groupKey);
        log.info("WriteCommand {} ({} {}) emitted for executable {} (group {})",
            command.commandId(), command.commandType(), command.operation(), localId, groupKey);
    }

    private static UUID parseLocalId(String aggregateId) {
        try {
            return UUID.fromString(aggregateId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID deterministicCommandId(UUID outboxEventId) {
        return UUID.nameUUIDFromBytes(
            (COMMAND_ID_NAMESPACE + outboxEventId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Deterministic command id disambiguated by a discriminator, so a single outbox event can
     * emit more than one command (the DELETE + CREATE of a kind transition) without collision,
     * while staying stable across drain retries.
     */
    private static UUID deterministicCommandId(UUID outboxEventId, String discriminator) {
        return UUID.nameUUIDFromBytes(
            (COMMAND_ID_NAMESPACE + outboxEventId + ":" + discriminator).getBytes(StandardCharsets.UTF_8));
    }
}
