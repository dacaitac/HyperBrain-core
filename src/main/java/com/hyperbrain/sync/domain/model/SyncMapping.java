package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain record representing a row in {@code sync_mappings}: the bridge between an external
 * entity (e.g. an EKReminder) and its local {@link CoreExecutable}.
 *
 * @param id                 surrogate key
 * @param userId             owning user
 * @param localId            UUID of the corresponding {@code core_executable} row
 * @param externalSystem     always {@code APPLE} for EventKit entities
 * @param externalId         EventKit identifier (entity_id from the SQS event)
 * @param lastKnownChecksum  SHA-256 of the last processed payload; used for change detection
 * @param syncStatus         short status label, e.g. {@code SYNCED}
 * @param lastSyncedAt       timestamp of the last successful sync
 */
public record SyncMapping(
    UUID id,
    UUID userId,
    UUID localId,
    String externalSystem,
    String externalId,
    String lastKnownChecksum,
    String syncStatus,
    OffsetDateTime lastSyncedAt
) {
}
