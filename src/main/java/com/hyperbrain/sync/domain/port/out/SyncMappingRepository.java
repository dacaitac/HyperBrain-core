package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.SyncMapping;

import java.util.Optional;

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
