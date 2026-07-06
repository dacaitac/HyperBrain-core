package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.ResultStatus;
import com.hyperbrain.sync.domain.model.WriteCommandResult;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire DTO for the {@code WriteCommandResult} message published by SentinelAPI on
 * {@code apple-commands-results.fifo} (AsyncAPI v1.2.0, ADR-010). Snake_case keys; unknown
 * fields ignored for forward-compatibility.
 *
 * @param schemaVersion contract version marker ("1")
 * @param commandId     correlation id of the applied WriteCommand
 * @param status        APPLIED or FAILED
 * @param operation     operation that was applied
 * @param entityId      native EventKit identifier (null when FAILED)
 * @param error         failure detail (null when APPLIED)
 * @param appliedAt     when SentinelAPI applied the command
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WriteCommandResultMessage(
    @JsonProperty("schema_version") String schemaVersion,
    @JsonProperty("command_id") UUID commandId,
    @JsonProperty("status") ResultStatus status,
    @JsonProperty("operation") Operation operation,
    @JsonProperty("entity_id") String entityId,
    @JsonProperty("error") String error,
    @JsonProperty("applied_at") OffsetDateTime appliedAt
) {

    /** Converts the wire DTO into the domain record. */
    public WriteCommandResult toDomain() {
        return new WriteCommandResult(commandId, status, operation, entityId, error, appliedAt);
    }
}
