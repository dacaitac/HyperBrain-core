package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain event recorded when a materialized day yields no useful blocks (HU-01c H2 negative case):
 * the user must be told the day was planned for tomorrow instead, never left in silence (Triángulo de
 * Control). Written to the Transactional Outbox in the same transaction as the idempotency claim, so
 * the proposal is atomic with the materialization — either the empty day is claimed <em>and</em> the
 * notice is queued, or neither. This removes the crash window a post-commit publish left open.
 *
 * <p>It shares the {@code AGENDA_BLOCK} aggregate type with {@link AgendaBlockPlannedEvent} so the
 * same Apple propagator routes it; the {@code event_type} distinguishes the two. The propagator emits
 * the notice reminder with a command id derived deterministically from {@code (user, day)}, so an
 * at-least-once drain never doubles it.
 *
 * @param userId          the user to notify; never null
 * @param targetDay       the empty day being reported; never null
 * @param zoneId          the user's timezone id; never blank
 * @param energyCriterion the readable energy-trim chain to surface in the notice; never blank
 * @param referenceInstant the reference instant for the reminder payload; never null
 */
public record EmptyAgendaProposedEvent(
    UUID userId,
    LocalDate targetDay,
    String zoneId,
    String energyCriterion,
    OffsetDateTime referenceInstant
) {

    /** Outbox {@code aggregate_type}: shared with {@link AgendaBlockPlannedEvent} for propagator routing. */
    public static final String AGGREGATE_TYPE = AgendaBlockPlannedEvent.AGGREGATE_TYPE;

    /** Outbox {@code event_type} (past participle). */
    public static final String EVENT_TYPE = "EmptyAgendaProposedEvent";

    public EmptyAgendaProposedEvent {
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
        if (referenceInstant == null) {
            throw new IllegalArgumentException("referenceInstant must not be null");
        }
    }
}
