package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EventRouter")
class EventRouterTest {

    @Test
    @DisplayName("routes each event to the handler registered for its entity type")
    void routes_to_matching_handler() {
        // Given
        RecordingHandler reminder = new RecordingHandler(EntityType.REMINDER);
        RecordingHandler calendar = new RecordingHandler(EntityType.CALENDAR_EVENT);
        EventRouter router = new EventRouter(List.of(reminder, calendar));
        SentinelEvent event = eventOf(EntityType.REMINDER);

        // When
        router.route(event);

        // Then
        assertThat(reminder.handled).containsExactly(event);
        assertThat(calendar.handled).isEmpty();
    }

    @Test
    @DisplayName("skips silently when no handler is registered for the entity type")
    void skips_when_no_handler_registered() {
        // Given
        RecordingHandler reminder = new RecordingHandler(EntityType.REMINDER);
        EventRouter router = new EventRouter(List.of(reminder));
        SentinelEvent unhandled = eventOf(EntityType.CALENDAR_EVENT);

        // When / Then
        assertThatCode(() -> router.route(unhandled)).doesNotThrowAnyException();
        assertThat(reminder.handled).isEmpty();
    }

    private static SentinelEvent eventOf(EntityType type) {
        return new SentinelEvent(
            "1",
            UUID.randomUUID().toString(),
            "APPLE",
            type,
            "EKReminder-" + type,
            Operation.CREATED,
            OffsetDateTime.parse("2026-07-04T15:30:00-05:00"),
            "{\"title\":\"test\"}");
    }

    /** Fake handler that records the events it received, avoiding mocking framework for a pure unit test. */
    private static final class RecordingHandler implements IEventHandler {
        private final EntityType type;
        private final java.util.List<SentinelEvent> handled = new java.util.ArrayList<>();

        private RecordingHandler(EntityType type) {
            this.type = type;
        }

        @Override
        public EntityType supportedType() {
            return type;
        }

        @Override
        public void handle(SentinelEvent event) {
            handled.add(event);
        }
    }
}
