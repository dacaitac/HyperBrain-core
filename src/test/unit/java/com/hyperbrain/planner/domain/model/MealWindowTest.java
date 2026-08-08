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

    @Test
    @DisplayName("resolves the plausible band to concrete instants in the user's zone")
    void resolves_the_plausible_band() {
        // Given: lunch 12:30-13:30, plausible anywhere between 11:30 and 14:30
        MealWindow lunch = new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            LocalTime.of(11, 30), LocalTime.of(14, 30));

        // When
        RetimingBand band = lunch.toBand(LocalDate.of(2026, 7, 10), ZoneId.of("America/Bogota"));

        // Then
        assertThat(band.label()).isEqualTo("lunch");
        assertThat(band.start())
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 11, 30, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(band.end())
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 14, 30, 0, 0, ZoneOffset.ofHours(-5)));
    }

    @Test
    @DisplayName("a meal configured without a band is rigid: its band is its own hour")
    void a_meal_without_a_band_is_rigid() {
        // Given: nothing was said about where else this meal could sit
        MealWindow lunch = new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30));

        // Then
        assertThat(lunch.bandStart()).isEqualTo(lunch.start());
        assertThat(lunch.bandEnd()).isEqualTo(lunch.end());
    }

    @Test
    @DisplayName("rejects a band that does not enclose its own meal window")
    void rejects_a_band_narrower_than_its_meal() {
        // A band narrower than the anchor would forbid the very hour the meal is configured for.
        assertThatThrownBy(() -> new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            LocalTime.of(13, 0), LocalTime.of(14, 30)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            LocalTime.of(11, 30), LocalTime.of(13, 0)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a band flush with its own meal window is legal — the enclosure is inclusive")
    void a_band_flush_with_its_window_is_legal() {
        // The boundary of the enclosure rule, and the shape the rigid constructor produces.
        MealWindow flush = new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            LocalTime.of(12, 30), LocalTime.of(13, 30));

        assertThat(flush.bandStart()).isEqualTo(flush.start());
        assertThat(flush.bandEnd()).isEqualTo(flush.end());
    }

    @Test
    @DisplayName("rejects a band with a missing edge — a half-configured band is not a band")
    void rejects_a_half_configured_band() {
        assertThatThrownBy(() -> new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            null, LocalTime.of(14, 30)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            LocalTime.of(11, 30), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the plausible band never moves the anchor: the wall stays the hour kept free of work")
    void the_band_does_not_move_the_wall() {
        // Two different things carried by one record — widening where lunch may sit must not widen the
        // stretch the deterministic floor keeps clear.
        MealWindow lunch = new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30),
            LocalTime.of(11, 30), LocalTime.of(14, 30));
        ZoneId bogota = ZoneId.of("America/Bogota");

        OccupiedInterval wall = lunch.toWall(LocalDate.of(2026, 7, 10), bogota);

        assertThat(wall.start())
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 12, 30, 0, 0, ZoneOffset.ofHours(-5)));
        assertThat(wall.end())
            .isEqualTo(OffsetDateTime.of(2026, 7, 10, 13, 30, 0, 0, ZoneOffset.ofHours(-5)));
    }

    @Test
    @DisplayName("resolving a band without a day or a zone fails loudly")
    void rejects_a_band_resolution_without_day_or_zone() {
        MealWindow lunch = new MealWindow("lunch", LocalTime.of(12, 30), LocalTime.of(13, 30));

        assertThatThrownBy(() -> lunch.toBand(null, ZoneId.of("America/Bogota")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lunch.toBand(LocalDate.of(2026, 7, 10), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
