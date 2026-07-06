package com.hyperbrain.sync.domain.model;

import java.util.UUID;

/**
 * Persisted correlation record of an emitted {@link WriteCommand} ({@code sync_write_commands}).
 * Bridges the asynchronous gap between publishing a command and consuming its
 * {@link WriteCommandResult}: on CREATED the {@code localId} identifies which
 * {@code core_executable} the returned EventKit id belongs to, and {@code payloadJson}
 * replays the checksum once the id is known (ADR-010).
 *
 * @param commandId   correlation id (primary key)
 * @param userId      owning user
 * @param localId     UUID of the {@code core_executable} the command refers to
 * @param commandType payload discriminator
 * @param operation   requested operation
 * @param entityId    EventKit identifier; {@code null} until a CREATED result arrives
 * @param payloadJson wire JSON of the payload as sent; {@code null} for DELETED
 * @param status      {@code PENDING}, {@code APPLIED} or {@code FAILED}
 */
public record PendingWriteCommand(
    UUID commandId,
    UUID userId,
    UUID localId,
    CommandType commandType,
    Operation operation,
    String entityId,
    String payloadJson,
    String status
) {
}
