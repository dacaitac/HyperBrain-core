package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of a {@link WriteCommand} applied by SentinelAPI, consumed from
 * {@code apple-commands-results.fifo} (ADR-010). On {@code CREATED} it carries the
 * EventKit identifier the Core needs to close the {@code sync_mapping}.
 *
 * @param commandId correlates with the originating {@link WriteCommand}
 * @param status    outcome of applying the command
 * @param operation operation that was applied
 * @param entityId  native EventKit identifier; {@code null} when {@code FAILED}
 * @param error     failure detail when {@code status = FAILED}
 * @param appliedAt when SentinelAPI applied the command
 */
public record WriteCommandResult(
    UUID commandId,
    ResultStatus status,
    Operation operation,
    String entityId,
    String error,
    OffsetDateTime appliedAt
) {
}
