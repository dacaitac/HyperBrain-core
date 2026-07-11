package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Domain event recorded once per successful agenda generation (HU-01b delivery slice): the day's
 * {@code PLANNED} blocks were persisted and must be delivered to the user's iOS as reminders. Written
 * to the Transactional Outbox in the same transaction as the blocks, so delivery is atomic with the
 * plan — either the day is planned <em>and</em> staged for write-back, or neither.
 *
 * <p>The event carries only the coordinates the {@code AgendaBlockPropagator} needs to re-read the
 * day ({@code userId}, {@code targetDay}, {@code zoneId}) plus the readable {@code energyCriterion}
 * chain (Sleep Score → margin → quota) that is a per-day property of the run and has no per-block
 * home. The blocks themselves are re-read from the store at drain time (the propagator mirrors current
 * state, never a stale snapshot).
 *
 * @param userId          the user whose day was planned; never null
 * @param targetDay       the calendar day that was planned; never null
 * @param zoneId          the user's timezone id used to bound the local day; never blank
 * @param energyCriterion the readable Sleep Score → margin → quota chain; never blank
 */
public record AgendaBlockPlannedEvent(
    UUID userId,
    LocalDate targetDay,
    String zoneId,
    String energyCriterion
) {

    /** Outbox {@code aggregate_type} of an agenda-block delivery event. */
    public static final String AGGREGATE_TYPE = "AGENDA_BLOCK";

    /** Outbox {@code event_type} (past participle). */
    public static final String EVENT_TYPE = "AgendaBlockPlannedEvent";

    public AgendaBlockPlannedEvent {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (targetDay == null) {
            throw new IllegalArgumentException("targetDay must not be null");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId must not be blank");
        }
        if (energyCriterion == null || energyCriterion.isBlank()) {
            throw new IllegalArgumentException("energyCriterion must not be blank");
        }
    }
}
