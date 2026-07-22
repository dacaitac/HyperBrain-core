package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain job emitted to {@code ia-jobs} to request the materialization of a day's agenda (HU-01c
 * H2 — single-owner materialization). It is the seam that decouples the <em>triggers</em> of a plan
 * (the morning wake edge, the manual «calcular» button) from the <em>single consumer</em> that
 * actually generates and persists it ({@code AgendaJobConsumer}). Written to the Transactional
 * Outbox with {@code aggregate_type = IA_JOB} in the same transaction as the trigger's own guard
 * (the once-per-day morning state, or the user-command dedup), so a job is queued if and only if the
 * trigger fired.
 *
 * <p>The job carries only what the consumer needs to reconstruct the work deterministically; every
 * heavier input (ranked tasks, walls, WIG, sleep window, energy) is re-read from the aggregates at
 * materialization time, so the payload never goes stale:
 * <ul>
 *   <li>{@code userId} — whose day to plan;</li>
 *   <li>{@code agendaDate} — the primary day being planned (today for the morning run, the replan's
 *       start day for a «calcular» run);</li>
 *   <li>{@code zoneId} — the timezone the trigger resolved the local day in, carried so the consumer
 *       reconstructs the exact same day boundaries the trigger saw;</li>
 *   <li>{@code referenceInstant} — the instant {@code T} the plan is anchored to (wall clock at
 *       dispatch for the morning run, {@code occurred_at} of the button press for a replan). It is
 *       frozen here, never re-read from a clock in the consumer, so a redelivery reconstructs the
 *       identical planning window (and therefore the identical idempotency hash);</li>
 *   <li>{@code fromNow} — false for a full-day morning run (lower bound = wake), true for a
 *       replan-from-now run (lower bound = {@code max(wake, T)}, multi-day cross-day dedup, no
 *       empty-day proposal).</li>
 * </ul>
 *
 * @param userId           the user whose day to materialize; never null
 * @param agendaDate       the primary calendar day to plan; never null
 * @param zoneId           the timezone id used to bound the local day; never blank
 * @param referenceInstant the anchor instant {@code T}, frozen at dispatch; never null
 * @param fromNow          true for a replan-from-now run, false for a full-day morning run
 */
public record DailyAgendaRequestedEvent(
    UUID userId,
    LocalDate agendaDate,
    String zoneId,
    OffsetDateTime referenceInstant,
    boolean fromNow
) {

    /** Outbox {@code aggregate_type}: routes the event to {@code ia-jobs} (see {@code SqsEventPublisher}). */
    public static final String AGGREGATE_TYPE = "IA_JOB";

    /** Outbox {@code event_type} (past participle). */
    public static final String EVENT_TYPE = "DailyAgendaRequestedEvent";

    public DailyAgendaRequestedEvent {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (agendaDate == null) {
            throw new IllegalArgumentException("agendaDate must not be null");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId must not be blank");
        }
        if (referenceInstant == null) {
            throw new IllegalArgumentException("referenceInstant must not be null");
        }
    }
}
