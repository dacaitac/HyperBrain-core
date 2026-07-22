package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EmptyAgendaProposedEvent — outbox-staged next-day proposal (HU-01c H2)")
class EmptyAgendaProposedEventTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime T = OffsetDateTime.of(2026, 7, 10, 6, 41, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("shares the AGENDA_BLOCK aggregate so the same Apple propagator routes it")
    void holds_fields_and_routing() {
        EmptyAgendaProposedEvent event =
            new EmptyAgendaProposedEvent(USER, DAY, "America/Bogota", "NEUTRAL", T);

        assertThat(event.userId()).isEqualTo(USER);
        assertThat(event.targetDay()).isEqualTo(DAY);
        assertThat(event.zoneId()).isEqualTo("America/Bogota");
        assertThat(event.energyCriterion()).isEqualTo("NEUTRAL");
        assertThat(event.referenceInstant()).isEqualTo(T);
        assertThat(EmptyAgendaProposedEvent.AGGREGATE_TYPE)
            .isEqualTo(AgendaBlockPlannedEvent.AGGREGATE_TYPE);
        assertThat(EmptyAgendaProposedEvent.EVENT_TYPE).isEqualTo("EmptyAgendaProposedEvent");
    }

    @Test
    @DisplayName("rejects a blank energy criterion (legibility is obligatory)")
    void rejects_blank_criterion() {
        assertThatThrownBy(() -> new EmptyAgendaProposedEvent(USER, DAY, "UTC", "  ", T))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("energyCriterion");
    }

    @Test
    @DisplayName("rejects a null reference instant")
    void rejects_null_reference() {
        assertThatThrownBy(() -> new EmptyAgendaProposedEvent(USER, DAY, "UTC", "NEUTRAL", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("referenceInstant");
    }
}
