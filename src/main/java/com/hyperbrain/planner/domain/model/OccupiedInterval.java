package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A stretch of the day already spoken for — a hard wall the generator plans around and the
 * {@code AgendaValidator} re-checks: an existing open/settled {@code core_time_block}, or a
 * read-only AGENDA executable's window (ADR-009). The Planner never schedules on top of these.
 *
 * @param executableId the executable owning the wall (a block's or an AGENDA's); may be null when the
 *                     wall is not tied to one executable
 * @param start        wall start; never null
 * @param end          wall end; never null, strictly after {@code start}
 * @param readOnlyAgenda true when the wall is a read-only AGENDA window (ADR-009)
 */
public record OccupiedInterval(
    UUID executableId,
    OffsetDateTime start,
    OffsetDateTime end,
    boolean readOnlyAgenda
) {

    public OccupiedInterval {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be strictly after start: " + start + " .. " + end);
        }
    }

    /**
     * Tells whether a candidate half-open interval {@code [candidateStart, candidateEnd)} overlaps
     * this wall.
     *
     * @param candidateStart candidate start; never null
     * @param candidateEnd   candidate end; never null
     * @return true when the two intervals overlap
     */
    public boolean overlaps(OffsetDateTime candidateStart, OffsetDateTime candidateEnd) {
        return candidateStart.isBefore(end) && candidateEnd.isAfter(start);
    }
}
