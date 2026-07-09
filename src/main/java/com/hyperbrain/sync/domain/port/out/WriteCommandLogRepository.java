package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: persistence of the write-command correlation log ({@code sync_write_commands}).
 * Every emitted {@link com.hyperbrain.sync.domain.model.WriteCommand} is recorded before
 * publishing; its {@link com.hyperbrain.sync.domain.model.WriteCommandResult} resolves the row
 * (ADR-010).
 */
public interface WriteCommandLogRepository {

    /**
     * Inserts (or refreshes, when retried with the same deterministic {@code commandId}) a
     * pending command row. Must run in the same transaction as the outbox drain that emits it.
     *
     * @param command the pending record to persist
     */
    void upsertPending(PendingWriteCommand command);

    /**
     * Returns the command with the given correlation id, or empty if unknown.
     *
     * @param commandId the correlation id
     * @return the record, or empty
     */
    Optional<PendingWriteCommand> findById(UUID commandId);

    /**
     * Returns the pending CREATED command for a local executable, if one is still awaiting its
     * result — used to avoid emitting a second CREATE while the first is in flight.
     *
     * @param localId the {@code core_executable} id
     * @return the pending CREATED record, or empty
     */
    Optional<PendingWriteCommand> findPendingCreateByLocalId(UUID localId);

    /**
     * Returns the Apple entity kind of the most recent non-delete command written for a local
     * executable (any status) — i.e. the kind the mapped EventKit entity is, or is becoming.
     * Used to detect a reminder↔event transition when the executable type crosses that boundary;
     * looking at the latest written kind (not only APPLIED) prevents re-triggering the transition
     * on the burst of updates that follows before its results land.
     *
     * @param localId the {@code core_executable} id
     * @return the last written command type, or empty when there is no write history
     */
    Optional<CommandType> findLastWrittenCommandTypeByLocalId(UUID localId);

    /**
     * Marks a command as successfully applied, recording the EventKit identifier it resolved to.
     *
     * @param commandId  the correlation id
     * @param entityId   native EventKit identifier reported by SentinelAPI
     * @param resolvedAt when the result was processed
     */
    void markApplied(UUID commandId, String entityId, OffsetDateTime resolvedAt);

    /**
     * Marks a command as failed with the error reported by SentinelAPI.
     *
     * @param commandId  the correlation id
     * @param error      failure detail (may be null)
     * @param resolvedAt when the result was processed
     */
    void markFailed(UUID commandId, String error, OffsetDateTime resolvedAt);
}
