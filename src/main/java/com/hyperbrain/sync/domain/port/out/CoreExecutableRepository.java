package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.CoreExecutable;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: persistence of {@link CoreExecutable} domain records.
 * The JDBC adapter lives in the infrastructure layer; the domain never touches the ORM.
 */
public interface CoreExecutableRepository {

    /**
     * Inserts a new executable row. The caller is responsible for generating {@code id}.
     *
     * @param executable the record to persist
     */
    void insert(CoreExecutable executable);

    /**
     * Updates name, status, startTime, endTime, and sourceCalendar for an existing row.
     *
     * @param executable the record with updated values; matched by {@code id}
     */
    void update(CoreExecutable executable);

    /**
     * Returns the executable with the given id, or empty if it does not exist.
     *
     * @param id surrogate key
     * @return the record, or empty
     */
    Optional<CoreExecutable> findById(UUID id);

    /**
     * Deletes the executable with the given id. No-op if it does not exist.
     *
     * @param id surrogate key
     */
    void deleteById(UUID id);

    /**
     * Inserts or updates the full attribute set of the Notion inbound mapping (HU-14 CA-28):
     * {@code core_executable} columns plus the {@code core_execution_profile} scales, keyed
     * by {@code id}. The upsert makes CREATE-vs-UPDATE resolution independent of the webhook
     * event type.
     *
     * @param snapshot the state to persist
     */
    void upsert(ExecutableSnapshot snapshot);
}
