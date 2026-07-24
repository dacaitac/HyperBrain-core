package com.hyperbrain.planner.infrastructure.telemetry;

import com.hyperbrain.planner.domain.model.NormalizationStatus;

/**
 * Deferred normalization step of the raw-first pipeline (ADR-016): given a raw telemetry record,
 * projects it into a typed {@code tel_*} table and reports the resulting {@link NormalizationStatus}.
 * The caller ({@link TelemetryIngestionService}) writes that status back onto the raw row.
 */
interface TelemetryNormalizer {

    /**
     * Normalizes one raw record.
     *
     * @param record the landed raw envelope; never null
     * @return {@link NormalizationStatus#NORMALIZED} on success, {@link NormalizationStatus#SKIPPED}
     *         when no strategy matches its {@code (provider, event_type)}, or
     *         {@link NormalizationStatus#ERROR} when the matching strategy failed to interpret it
     */
    NormalizationStatus normalize(TelemetryRecord record);
}
