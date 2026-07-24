package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.NormalizationStatus;
import com.hyperbrain.planner.domain.model.RawTelemetryRow;

import java.util.Optional;
import java.util.UUID;

/**
 * Out-port for the raw-first telemetry landing zone ({@code context_event}, ADR-016). The
 * {@code TelemetryConsumer} persists every envelope here before any interpretation, so ingestion
 * never fails on payload format.
 */
public interface RawTelemetryStore {

    /**
     * Inserts a raw row with {@code normalization_status = PENDING}, deduplicating on the semantic
     * {@code dedup_key} via {@code ON CONFLICT DO NOTHING}. Returns the new row id when this call
     * inserted it, or empty when an equal {@code dedup_key} already exists (semantic duplicate — the
     * caller acks without normalizing). Using {@code ON CONFLICT} rather than catching a unique
     * violation keeps the surrounding transaction usable.
     *
     * @param row the raw envelope to land; never null
     * @return the inserted row id, or empty on a semantic duplicate
     */
    Optional<UUID> insertPending(RawTelemetryRow row);

    /**
     * Transitions a raw row's normalization status (PENDING → NORMALIZED / SKIPPED / ERROR).
     *
     * @param id     the raw row id; never null
     * @param status the new status; never null
     */
    void markStatus(UUID id, NormalizationStatus status);

    /**
     * Deletes NORMALIZED and SKIPPED raw rows older than the retention window (ERROR rows are kept
     * for diagnosis), using the retention partial index.
     *
     * @param retentionDays the retention window in days
     * @return the number of rows purged
     */
    int purgeProcessedOlderThan(int retentionDays);
}
