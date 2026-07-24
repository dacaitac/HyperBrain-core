package com.hyperbrain.planner.domain.model;

/**
 * Lifecycle of a raw {@code context_event} telemetry row (ADR-016 raw-first ingestion). Mirrors the
 * {@code normalization_status} CHECK constraint.
 *
 * <ul>
 *   <li>{@link #PENDING} — landed raw, not yet normalized.</li>
 *   <li>{@link #NORMALIZED} — a strategy projected it into a typed {@code tel_*} table.</li>
 *   <li>{@link #SKIPPED} — no strategy for its (provider, event_type); kept raw, never DLQ'd.</li>
 *   <li>{@link #ERROR} — a strategy failed to interpret the payload; kept for diagnosis/reprocessing.</li>
 * </ul>
 */
public enum NormalizationStatus {
    PENDING,
    NORMALIZED,
    SKIPPED,
    ERROR
}
