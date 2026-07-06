package com.hyperbrain.sync.domain.model;

/**
 * Marker for payloads that can travel inside a {@link WriteCommand} (TD-03, ADR-010).
 * Only reminders and calendar events are writable from the Core; the sealed hierarchy
 * makes any other payload type a compile-time error.
 */
public sealed interface WritePayload permits ReminderPayload, CalendarEventPayload {
}
