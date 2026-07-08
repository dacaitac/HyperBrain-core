package com.hyperbrain.core.domain.model;

/**
 * Lifecycle of a {@code core_time_block} row (ADR-013 D1). A block is planned, optionally
 * executed, and finally settled — it is never stretched, so an overrun can no longer overlap
 * the agenda.
 */
public enum TimeBlockStatus {
    /** Reserved by the Planner in a future agenda (HU-01). */
    PLANNED,
    /** Currently executing; the only state a focus switch can settle. */
    ACTIVE,
    /** Settled by a focus switch (DR-05/DR-08): frozen as the record of what was achieved. */
    SETTLED,
    /** Settled by the expiry scheduler once {@code date_end} passed (DR-08). */
    EXPIRED;

    /** @return true while the block can still be settled ({@link #PLANNED} or {@link #ACTIVE}) */
    public boolean isOpen() {
        return this == PLANNED || this == ACTIVE;
    }
}
