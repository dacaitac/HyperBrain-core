package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DailyAgendaRequestedEvent — ia-jobs materialization request (HU-01c H2)")
class DailyAgendaRequestedEventTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final OffsetDateTime T = OffsetDateTime.of(2026, 7, 10, 6, 41, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("carries the coordinates the single owner needs and routes to ia-jobs")
    void holds_fields_and_routing_constants() {
        DailyAgendaRequestedEvent event =
            new DailyAgendaRequestedEvent(USER, DAY, "America/Bogota", T, true);

        assertThat(event.userId()).isEqualTo(USER);
        assertThat(event.agendaDate()).isEqualTo(DAY);
        assertThat(event.zoneId()).isEqualTo("America/Bogota");
        assertThat(event.referenceInstant()).isEqualTo(T);
        assertThat(event.fromNow()).isTrue();
        assertThat(DailyAgendaRequestedEvent.AGGREGATE_TYPE).isEqualTo("IA_JOB");
        assertThat(DailyAgendaRequestedEvent.EVENT_TYPE).isEqualTo("DailyAgendaRequestedEvent");
    }

    @Test
    @DisplayName("rejects null userId")
    void rejects_null_user() {
        assertThatThrownBy(() -> new DailyAgendaRequestedEvent(null, DAY, "UTC", T, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("rejects null agendaDate")
    void rejects_null_day() {
        assertThatThrownBy(() -> new DailyAgendaRequestedEvent(USER, null, "UTC", T, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agendaDate");
    }

    @Test
    @DisplayName("rejects blank zoneId")
    void rejects_blank_zone() {
        assertThatThrownBy(() -> new DailyAgendaRequestedEvent(USER, DAY, "  ", T, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zoneId");
    }

    @Test
    @DisplayName("rejects null referenceInstant")
    void rejects_null_reference() {
        assertThatThrownBy(() -> new DailyAgendaRequestedEvent(USER, DAY, "UTC", null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("referenceInstant");
    }
}
