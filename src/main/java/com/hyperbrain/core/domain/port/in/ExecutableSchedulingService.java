package com.hyperbrain.core.domain.port.in;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Published interface of the {@code core} module for <b>re-timing an executable inside its own day</b>
 * (ADR-040 D9, the middle tier of the rigidity hierarchy).
 *
 * <p>Three levels of rigidity govern the day. Tasks of type agenda are untouchable, in hour and in day
 * alike — they are the floor the rest is laid against. Everything ordinary is placeable. In between sit
 * <b>activities and study sessions</b>: they carry a calendar window of their own, so they are never
 * put inside a block, but they can be moved in hour — precisely because something may have interrupted
 * Daniel right before one was due to start, and it then needs re-timing.
 *
 * <p><b>The day never moves.</b> That is the boundary of the authority the planner recovers here, and
 * it is enforced rather than trusted: an attempt to write a window that lands on a different local day
 * is refused. The earlier decision that took away the planner's ability to write these hours at all is
 * amended, not reversed — the committee's objection was to the unbounded version of that power, and the
 * bound by day is the answer to it.
 */
public interface ExecutableSchedulingService {

    /**
     * Re-times an executable inside the day it already stands on.
     *
     * <p>Writes only when the window genuinely differs, so a plan that changes nothing announces
     * nothing (ADR-040 D17). Participates in the caller's transaction and requires one.
     *
     * @param executableId the executable to re-time; never null
     * @param start        the new start instant; never null
     * @param end          the new end instant; may be null for a type that carries none
     * @param zone         the timezone the local day is reasoned in; never null
     * @return true when the row actually moved (and its move was announced to the satellites)
     * @throws IllegalArgumentException when the new window lands on a different local day than the one
     *                                  the executable already stands on — the day is the user's
     */
    boolean retimeWithinDay(UUID executableId, OffsetDateTime start, OffsetDateTime end, ZoneId zone);
}
