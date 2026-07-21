package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MealWindow (H1 rule 2, protected meal anchor)")
class MealWindowTest {

    @Test
    @DisplayName("resolves the local window to a concrete same-day wall in the user's zone")
    void resolves_local_window_to_wall_in_zone() {
        // Given: lunch 12:30-13:30 local, a Bogota user (UTC-5) on 2026-07-10
        MealWindow lunch = new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30));
        ZoneId bogota = ZoneId.of("America/Bogota");

        // When
        OccupiedInterval wall = lunch.toWall(LocalDate.of(2026, 7, 10), bogota);

        // Then: the wall lands at 12:30-13:30 Bogota (17:30-18:30 UTC), carries no executable, is not
        // a read-only AGENDA
        assertThat(wall.start()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 12, 30, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(wall.end()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 13, 30, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(wall.executableId()).isNull();
        assertThat(wall.readOnlyAgenda()).isFalse();
    }

    @Test
    @DisplayName("rejects a window whose end is not strictly after its start")
    void rejects_non_positive_window() {
        assertThatThrownBy(() -> new MealWindow("x", LocalTime.of(13, 0), LocalTime.of(13, 0)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a blank label")
    void rejects_blank_label() {
        assertThatThrownBy(() -> new MealWindow(" ", LocalTime.of(12, 0), LocalTime.of(13, 0)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
