package com.hyperbrain.planner.domain.model;

import java.util.UUID;

/**
 * One wall a proposed block tried to pass and the {@code AgendaValidator} rejected. Reported so the
 * rejection is never silent — the validator is the non-negotiable guard, and its findings surface
 * alongside the corrected agenda.
 *
 * @param executableId the offending block's executable; never null
 * @param wall         which wall the block violated; never null
 */
public record ValidationViolation(UUID executableId, Wall wall) {

    /** The hard walls the validator re-imposes (planner engine doc, ADR-009/ADR-013 D2). */
    public enum Wall {
        /** Placed outside the sleep frontier {@code [wake, bedtime]} (ADR-013 D2). */
        OUTSIDE_SLEEP_FRONTIER,
        /** Overlapped an occupied/SETTLED block or another placed block. */
        OVERLAPS_OCCUPIED,
        /** Overlapped a read-only AGENDA window (ADR-009). */
        OVERLAPS_READ_ONLY_AGENDA,
        /** A high-load block beyond the F6 quota (the WIG is exempt). */
        HIGH_LOAD_QUOTA_EXCEEDED,
        /** Scheduled a read-only AGENDA executable as if it were work (ADR-009). */
        SCHEDULES_READ_ONLY_AGENDA
    }

    public ValidationViolation {
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
        if (wall == null) {
            throw new IllegalArgumentException("wall must not be null");
        }
    }
}
