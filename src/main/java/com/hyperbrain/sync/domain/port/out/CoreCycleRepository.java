package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.CycleSnapshot;

import java.util.UUID;

/**
 * Outbound port: persistence of {@code core_cycle} rows for the Notion inbound sync
 * (HU-14, ADR-011: Cycles sync is fully bidirectional).
 */
public interface CoreCycleRepository {

    /**
     * Inserts or updates a cycle, keyed by {@code id} (HU-14 CA-28).
     *
     * @param snapshot the state to persist
     */
    void upsert(CycleSnapshot snapshot);

    /**
     * Deletes the cycle with the given id. No-op if it does not exist; executables that
     * referenced it keep running with {@code cycle_id = NULL} (DDL {@code ON DELETE SET NULL}).
     *
     * @param id surrogate key
     */
    void deleteById(UUID id);
}
