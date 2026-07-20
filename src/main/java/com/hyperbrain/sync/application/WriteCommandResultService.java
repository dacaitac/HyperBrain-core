package com.hyperbrain.sync.application;

import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.ResultStatus;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.WriteCommandResult;
import com.hyperbrain.sync.domain.port.out.CoreExecutableRepository;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes the write-back loop (ADR-010): processes {@code WriteCommandResult}s consumed from
 * {@code apple-commands-results.fifo}, correlating by {@code command_id} against
 * {@code sync_write_commands}.
 *
 * <p>On {@code APPLIED}: CREATED inserts the {@code sync_mapping} with the EventKit id echoed
 * back by SentinelAPI; UPDATED refreshes {@code last_known_checksum} + {@code last_synced_at}
 * (so the Apple echo of our own write is discarded, CA-7); DELETED removes the mapping.
 * On {@code FAILED} the command row keeps the error for diagnostics (HU-11) and the mapping is
 * left untouched.
 *
 * <p>Idempotent per {@code command_id + status} via {@code processed_message} (SQS delivers
 * at-least-once); the whole handling runs in one transaction so a failure rolls back the dedup
 * insert and lets SQS redeliver.
 */
@Service
public class WriteCommandResultService {

    private static final Logger log = LoggerFactory.getLogger(WriteCommandResultService.class);

    private static final String EXTERNAL_SYSTEM = "APPLE";
    private static final String SYNC_STATUS = "SYNCED";
    private static final String DEDUP_PREFIX = "write-command-result:";

    private final ProcessedMessageStore processedMessageStore;
    private final WriteCommandLogRepository commandLogRepo;
    private final SyncMappingRepository syncMappingRepo;
    private final CoreExecutableRepository executableRepo;

    public WriteCommandResultService(
        ProcessedMessageStore processedMessageStore,
        WriteCommandLogRepository commandLogRepo,
        SyncMappingRepository syncMappingRepo,
        CoreExecutableRepository executableRepo
    ) {
        this.processedMessageStore = processedMessageStore;
        this.commandLogRepo = commandLogRepo;
        this.syncMappingRepo = syncMappingRepo;
        this.executableRepo = executableRepo;
    }

    /**
     * Processes one result: deduplicates, resolves the pending command and applies the
     * corresponding {@code sync_mapping} transition.
     *
     * @param result the deserialized result
     */
    @Transactional
    public void handle(WriteCommandResult result) {
        String dedupKey = DEDUP_PREFIX + result.commandId() + ":" + result.status();
        if (!processedMessageStore.markProcessed(dedupKey, "WriteCommandResult")) {
            log.warn("Duplicate WriteCommandResult {} ({}) ignored", result.commandId(), result.status());
            return;
        }

        Optional<PendingWriteCommand> command = commandLogRepo.findById(result.commandId());
        if (command.isEmpty()) {
            log.warn("WriteCommandResult {} has no matching sync_write_commands row; ignored",
                result.commandId());
            return;
        }

        if (result.status() == ResultStatus.FAILED) {
            commandLogRepo.markFailed(result.commandId(), result.error(), OffsetDateTime.now());
            log.error("WriteCommand {} ({} {}) FAILED on SentinelAPI: {}",
                result.commandId(), command.get().commandType(), result.operation(), result.error());
            return;
        }

        applyResult(command.get(), result);
        commandLogRepo.markApplied(result.commandId(), result.entityId(), OffsetDateTime.now());
        log.info("WriteCommand {} ({} {}) APPLIED; entity {}",
            result.commandId(), command.get().commandType(), result.operation(), result.entityId());
    }

    private void applyResult(PendingWriteCommand command, WriteCommandResult result) {
        switch (result.operation()) {
            case CREATED -> closeMapping(command, result);
            case UPDATED -> refreshMapping(command, result);
            case DELETED -> syncMappingRepo.deleteByExternalSystemAndId(EXTERNAL_SYSTEM, result.entityId());
        }
    }

    private void closeMapping(PendingWriteCommand command, WriteCommandResult result) {
        String checksum = ChecksumCalculator.compute(
            result.entityId(), result.operation().name(), command.payloadJson());
        Optional<SyncMapping> existing =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, result.entityId());
        if (existing.isPresent()) {
            UUID orphanId = existing.get().localId();
            UUID canonicalId = command.localId();
            if (!orphanId.equals(canonicalId)) {
                // Echo-beat-result race: ReminderEventHandler created a transient entity for this
                // Apple entity before the WriteCommandResult arrived. Delete the orphan so its
                // pending outbox events (ReminderSyncedEvent) skip propagation in NotionEventPropagator
                // (propagateExecutableUpsert returns early when snapshotRepo.findExecutable returns empty).
                log.warn("Apple echo race for {}: orphan {} displaced by canonical {}; deleting orphan",
                    result.entityId(), orphanId, canonicalId);
                executableRepo.deleteById(orphanId);
            }
            syncMappingRepo.update(buildMapping(existing.get().id(), command, result, checksum));
        } else {
            syncMappingRepo.insert(buildMapping(UUID.randomUUID(), command, result, checksum));
        }
    }

    private void refreshMapping(PendingWriteCommand command, WriteCommandResult result) {
        String checksum = ChecksumCalculator.compute(
            result.entityId(), result.operation().name(), command.payloadJson());
        Optional<SyncMapping> existing =
            syncMappingRepo.findByExternalSystemAndId(EXTERNAL_SYSTEM, result.entityId());
        if (existing.isEmpty()) {
            log.warn("No sync_mapping for applied UPDATE on {}; creating one", result.entityId());
            syncMappingRepo.insert(buildMapping(UUID.randomUUID(), command, result, checksum));
            return;
        }
        syncMappingRepo.update(buildMapping(existing.get().id(), command, result, checksum));
    }

    private SyncMapping buildMapping(
        UUID id, PendingWriteCommand command, WriteCommandResult result, String checksum) {
        return new SyncMapping(
            id, command.userId(), command.localId(), EXTERNAL_SYSTEM,
            result.entityId(), checksum, SYNC_STATUS, OffsetDateTime.now());
    }
}
