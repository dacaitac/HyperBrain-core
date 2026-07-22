package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyperbrain.planner.domain.model.DailyAgendaRequestedEvent;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape of the {@code DailyAgendaRequestedEvent} payload carried inside the {@code ia-jobs}
 * envelope ({@code AgendaJobEmitter} serializes it). snake_case JSON; {@link #toDomain()} enforces
 * the contract and throws {@link IllegalArgumentException} on any violation, which the consumer turns
 * into a WARN + ack discard so a malformed job never poisons the DLQ.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DailyAgendaRequestedMessage(
    @JsonProperty("user_id") UUID userId,
    @JsonProperty("agenda_date") LocalDate agendaDate,
    @JsonProperty("zone_id") String zoneId,
    @JsonProperty("reference_instant") OffsetDateTime referenceInstant,
    @JsonProperty("from_now") boolean fromNow
) {

    /**
     * Maps the wire payload to the validated domain event.
     *
     * @return the domain event
     * @throws IllegalArgumentException when the payload violates the contract
     */
    DailyAgendaRequestedEvent toDomain() {
        return new DailyAgendaRequestedEvent(userId, agendaDate, zoneId, referenceInstant, fromNow);
    }
}
