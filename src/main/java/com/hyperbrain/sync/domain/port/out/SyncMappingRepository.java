package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.SyncMapping;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: persistence of {@link SyncMapping} records that bridge external entities
 * to their local {@code core_executable} counterparts.
 */
public interface SyncMappingRepository {

    /**
     * Returns the mapping for a given external system + external id pair, or empty if none exists.
     *
     * @param externalSystem e.g. {@code "APPLE"}
     * @param externalId     the EventKit identifier
     * @return the mapping, or empty
     */
    Optional<SyncMapping> findByExternalSystemAndId(String externalSystem, String externalId);

    /**
     * Returns the mapping that links a local {@code core_executable} to the given external
     * system, or empty if the entity has no counterpart there yet.
     *
     * @param externalSystem e.g. {@code "APPLE"}
     * @param localId        the {@code core_executable} id
     * @return the mapping, or empty
     */
    Optional<SyncMapping> findByExternalSystemAndLocalId(String externalSystem, UUID localId);

    /**
     * Inserts a new mapping row. The caller is responsible for generating {@code id}.
     *
     * @param mapping the record to persist
     */
    void insert(SyncMapping mapping);

    /**
     * Updates checksum, syncStatus and lastSyncedAt for an existing mapping matched by
     * {@code externalSystem + externalId}.
     *
     * @param mapping the record with updated fields
     */
    void update(SyncMapping mapping);

    /**
     * Deletes the mapping identified by external system + external id. No-op if absent.
     *
     * @param externalSystem e.g. {@code "APPLE"}
     * @param externalId     the EventKit identifier
     */
    void deleteByExternalSystemAndId(String externalSystem, String externalId);
}
