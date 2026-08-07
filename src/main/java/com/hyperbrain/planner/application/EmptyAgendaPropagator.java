package com.hyperbrain.planner.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.EmptyAgendaProposedEvent;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import com.hyperbrain.shared.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The new home of the empty-day notice, and the <b>only</b> thing left of the planner's private
 * delivery channel.
 *
 * <p>That channel existed to write blocks to the calendar, and since a block became an executable the
 * standard propagators already do it — by type routing on Apple and as a mirror page on Notion — so
 * keeping a second route for the same thing was a duplicate waiting to write two calendar events for
 * one block under two different mappings. The notice, however, is not a block: it is a message telling
 * the user his day came out empty and was proposed for tomorrow, and it has no executable to ride on.
 * It needed a house of its own rather than dying with the channel.
 *
 * <p>Delivery stays exactly-once by construction: the notice is staged in the Transactional Outbox
 * atomically with the materialization claim, and {@link EmptyAgendaNotifier} derives the command id
 * from {@code (user, day)}, so an at-least-once drain re-emits the same command — which SQS FIFO and
 * SentinelAPI dedup absorb — instead of doubling the reminder.
 */
@Service
public class EmptyAgendaPropagator implements IEventPropagator {

    private static final Logger log = LoggerFactory.getLogger(EmptyAgendaPropagator.class);

    private final EmptyAgendaNotifier emptyAgendaNotifier;
    private final ObjectMapper objectMapper;

    public EmptyAgendaPropagator(EmptyAgendaNotifier emptyAgendaNotifier, ObjectMapper objectMapper) {
        this.emptyAgendaNotifier = emptyAgendaNotifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExternalSystem target() {
        return ExternalSystem.APPLE;
    }

    @Override
    public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
        return origin != ExternalSystem.UNKNOWN && entityType == SyncedEntityType.AGENDA_NOTICE;
    }

    @Override
    public void propagate(OutboxEvent event) {
        if (!EmptyAgendaProposedEvent.AGGREGATE_TYPE.equals(event.aggregateType())
            || !EmptyAgendaProposedEvent.EVENT_TYPE.equals(event.eventType())) {
            return;
        }
        JsonNode payload = parsePayload(event.payload());
        if (payload == null) {
            log.warn("EmptyAgendaProposedEvent {} has unparseable payload; skipping notice", event.id());
            return;
        }
        UUID userId = parseUuid(payload.path("user_id").asText(null));
        LocalDate targetDay = parseDate(payload.path("target_day").asText(null));
        String energyCriterion = payload.path("energy_criterion").asText("");
        OffsetDateTime referenceInstant = parseTimestamp(payload.path("reference_instant").asText(null));
        if (userId == null || targetDay == null || referenceInstant == null) {
            log.warn("EmptyAgendaProposedEvent {} has incomplete coordinates; skipping notice", event.id());
            return;
        }
        emptyAgendaNotifier.proposeNextDay(userId, targetDay, energyCriterion, referenceInstant);
    }

    private JsonNode parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private static OffsetDateTime parseTimestamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }
}
