package com.hyperbrain.core.application.event;

import com.hyperbrain.shared.outbox.OutboxEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Design pattern: static Factory Method
 * Reason: the SYSTEM-origin created/updated notification of a core_executable is emitted from
 * several rules and services with a byte-identical shape. Building it in one place keeps the
 * aggregate name, the event names, the origin marker and the payload contract single-sourced —
 * the propagators match on all four.
 */

/**
 * Factory of the plain {@code core_executable} change notifications appended to the Transactional
 * Outbox by SYSTEM-authored domain writes.
 *
 * <p>The {@code SYSTEM} origin is deliberate and load-bearing: it is the only origin both
 * propagators accept (the framework loop guard drops an event only for the system it came from),
 * so a SYSTEM change reaches Apple <b>and</b> Notion. The payload carries just the identity and the
 * operation — every propagator re-reads the current row and writes a full mirror (ADR-012 D3), so
 * the event is a notification, never a state transfer.
 */
public final class ExecutableOutboxEvents {

    /** Outbox aggregate name both propagators route {@code core_executable} changes by. */
    public static final String EXECUTABLE_AGGREGATE = "CORE_EXECUTABLE";

    /** Origin marker of a change authored by the system itself (RF-17 loop protection). */
    public static final String SOURCE_SYSTEM = "SYSTEM";

    private static final String CREATED_EVENT = "ExecutableCreatedEvent";
    private static final String UPDATED_EVENT = "ExecutableUpdatedEvent";
    private static final String DELETED_EVENT = "ExecutableDeletedEvent";

    private ExecutableOutboxEvents() {
    }

    /**
     * Builds the creation notification of a SYSTEM-authored executable (e.g. a DR-04 recurrence
     * clone), so both satellites materialize it.
     *
     * @param executableId the freshly persisted executable
     * @return the outbox row to append inside the caller's transaction
     */
    public static OutboxEvent created(UUID executableId) {
        return event(executableId, CREATED_EVENT);
    }

    /**
     * Builds the update notification of an executable whose persisted state changed, so both
     * satellites re-project the row.
     *
     * @param executableId the changed executable
     * @return the outbox row to append inside the caller's transaction
     */
    public static OutboxEvent updated(UUID executableId) {
        return event(executableId, UPDATED_EVENT);
    }

    /**
     * Builds the deletion notification of an executable the system itself withdrew (a planner block
     * dropped from the plan, ADR-040 D10), so both satellites remove their counterpart. Unlike the
     * upsert notifications this one is <b>not</b> re-read by the propagators — by the time it drains,
     * the row is gone — so it must be staged in the same transaction as the delete.
     *
     * @param executableId the withdrawn executable
     * @return the outbox row to append inside the caller's transaction
     */
    public static OutboxEvent deleted(UUID executableId) {
        return event(executableId, DELETED_EVENT);
    }

    private static OutboxEvent event(UUID executableId, String eventType) {
        String payload = String.format("{\"local_id\":\"%s\",\"operation\":\"%s\"}",
            executableId, operationOf(eventType));
        return new OutboxEvent(UUID.randomUUID(), EXECUTABLE_AGGREGATE, executableId.toString(),
            eventType, payload, SOURCE_SYSTEM, OffsetDateTime.now());
    }

    private static String operationOf(String eventType) {
        if (CREATED_EVENT.equals(eventType)) {
            return "CREATED";
        }
        return DELETED_EVENT.equals(eventType) ? "DELETED" : "UPDATED";
    }
}
