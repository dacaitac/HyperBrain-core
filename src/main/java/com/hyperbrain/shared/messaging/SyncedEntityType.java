package com.hyperbrain.shared.messaging;

/**
 * Coarse classification of the domain entity an outbox event describes, derived from the
 * {@code aggregate_type} column. Lets {@link IEventPropagator#shouldPropagate} decide
 * eligibility without loading the entity (HU-14 CA-10/CA-12); finer-grained rules that need
 * the persisted row (e.g. ADR-009 ACTIVITY vs AGENDA) stay inside each propagator.
 */
public enum SyncedEntityType {
    EXECUTABLE,
    CYCLE,
    /** A planned agenda block delivered to Apple as a reminder (HU-01b morning write-back). */
    AGENDA_BLOCK,
    OTHER;

    /**
     * Maps an outbox {@code aggregate_type} to its entity classification.
     *
     * @param aggregateType the raw column value; may be {@code null}
     * @return the matching classification, or {@link #OTHER}
     */
    public static SyncedEntityType fromAggregateType(String aggregateType) {
        if (aggregateType == null) {
            return OTHER;
        }
        return switch (aggregateType) {
            case "CORE_EXECUTABLE", "TASK", "SYNC_APPLE" -> EXECUTABLE;
            case "CORE_CYCLE" -> CYCLE;
            case "AGENDA_BLOCK" -> AGENDA_BLOCK;
            default -> OTHER;
        };
    }
}
