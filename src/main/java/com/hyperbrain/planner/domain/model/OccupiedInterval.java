package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A stretch of the day already spoken for — a hard wall the generator plans around and the
 * {@code AgendaValidator} re-checks: an existing {@code TIME_BLOCK} executable's window (ADR-039), a
 * read-only AGENDA executable's window (ADR-009), or a standing commitment's window (an
 * {@code ACTIVITY} or {@code LEARNING_SESSION}, ADR-040 D9). The Planner never schedules on top of
 * these.
 *
 * <p><b>A wall says the time is taken, not that it can never move.</b> The rigidity of what owns it is
 * a separate question, answered elsewhere: an AGENDA window is untouchable, a commitment may still be
 * sent its hour back by the rescue. Neither is available to be planned over while it stands.
 *
 * @param executableId the executable owning the wall — the block itself for a {@code TIME_BLOCK}, the
 *                     event for an AGENDA, the commitment for an {@code ACTIVITY}; may be null when
 *                     the wall is not tied to one executable (a meal anchor, a transition buffer)
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
