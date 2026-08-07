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
    /**
     * A notice about the day itself rather than about an entity — today, only the empty-day proposal.
     * It has no executable to ride on, which is why it keeps a classification of its own.
     *
     * <p>The block classifications that used to sit here are gone: since a block became an executable
     * it is delivered through {@link #EXECUTABLE} like everything else, and a second classification
     * for the same rows only bought a second route writing the same calendar event twice.
     */
    AGENDA_NOTICE,
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
            case "AGENDA_BLOCK" -> AGENDA_NOTICE;
            default -> OTHER;
        };
    }
}
