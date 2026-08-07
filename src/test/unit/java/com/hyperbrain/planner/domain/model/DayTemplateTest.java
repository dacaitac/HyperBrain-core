package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DayTemplate — the day's shape as a soft reference (ADR-040 D2)")
class DayTemplateTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 7);

    @Test
    @DisplayName("the sanctioned template lays every ADR-040 slot in order, without gaps in its span")
    void default_template_matches_the_sanctioned_table() {
        // When
        List<TemplateSlot> slots = DayTemplate.DEFAULT.slots();

        // Then
        assertThat(slots).extracting(TemplateSlot::id).containsExactly(
            "WAKE_MARGIN", "PERSONAL_ROUTINE", "DAILY_STANDUP", "MORNING_BUFFER", "GOAL_MORNING",
            "LONG_BREAK", "WORK_MORNING", "LUNCH", "WHIRLWIND_LIGHT", "FIXED_MEETING",
            "MEETING_ZONE", "FREE_EVENING", "HOUSEHOLD", "WIND_DOWN");
        assertThat(slots.get(0).startMinute()).isEqualTo(6 * 60);
        assertThat(slots.get(slots.size() - 1).endMinute()).isEqualTo(22 * 60);
        for (int index = 1; index < slots.size(); index++) {
            assertThat(slots.get(index).startMinute()).isEqualTo(slots.get(index - 1).endMinute());
        }
    }

    @Test
    @DisplayName("only the agenda bands are closed to the generator (Daniel, 2026-08-07)")
    void only_the_agenda_bands_are_closed() {
        // When
        List<String> closed = DayTemplate.DEFAULT.slots().stream()
            .filter(slot -> !slot.schedulable())
            .map(TemplateSlot::id)
            .toList();

        // Then: the single restriction is time already spoken for by an agenda element. Everything
        // else — including the household band ADR-040 D19 had vetoed and the cushion after the
        // stand-up — is placeable, and the user may put whatever he wants wherever he wants.
        assertThat(closed).containsExactly("DAILY_STANDUP", "FIXED_MEETING");
        assertThat(slot("HOUSEHOLD").schedulable()).isTrue();
        assertThat(slot("MORNING_BUFFER").schedulable()).isTrue();
        assertThat(slot("WIND_DOWN").schedulable()).isTrue();
        assertThat(slot("MEETING_ZONE").schedulable()).isTrue();
    }

    @Test
    @DisplayName("the two-hour goal and work slots are DEEP windows; the whirlwind is never sized")
    void sizes_apply_to_goals_and_work_only() {
        // Then
        assertThat(slot("GOAL_MORNING").windowSize()).isEqualTo(WindowSize.DEEP);
        assertThat(slot("WORK_MORNING").windowSize()).isEqualTo(WindowSize.DEEP);
        assertThat(slot("WHIRLWIND_LIGHT").windowSize()).isEqualTo(WindowSize.UNSIZED);
        assertThat(slot("FREE_EVENING").windowSize()).isEqualTo(WindowSize.UNSIZED);
    }

    @Test
    @DisplayName("waking at the anchor hour resolves the template onto its own wall-clock bands")
    void resolving_at_the_anchor_wake_keeps_the_template_hours() {
        // Given
        OffsetDateTime wake = at(7, 0);

        // When
        List<DayWindow> windows = DayTemplate.DEFAULT.resolve(DAY, BOGOTA, wake);

        // Then
        DayWindow goal = window(windows, "GOAL_MORNING");
        assertThat(goal.start()).isEqualTo(at(8, 30));
        assertThat(goal.end()).isEqualTo(at(10, 30));
    }

    @Test
    @DisplayName("waking late slides the whole day by the same amount — the agenda runs, it is not rebuilt")
    void a_late_wake_shifts_every_slot_uniformly() {
        // Given: up at 09:15 instead of the 07:00 anchor — two hours and a quarter late.
        OffsetDateTime wake = at(9, 15);

        // When
        List<DayWindow> windows = DayTemplate.DEFAULT.resolve(DAY, BOGOTA, wake);

        // Then
        assertThat(window(windows, "PERSONAL_ROUTINE").start()).isEqualTo(at(9, 15));
        assertThat(window(windows, "GOAL_MORNING").start()).isEqualTo(at(10, 45));
        assertThat(window(windows, "GOAL_MORNING").end()).isEqualTo(at(12, 45));
        assertThat(window(windows, "WIND_DOWN").end()).isEqualTo(DAY.plusDays(1)
            .atStartOfDay(BOGOTA).toOffsetDateTime().plusMinutes(15));
        // Every window keeps its template duration: the day slides, it does not compress.
        assertThat(windows).allSatisfy(w ->
            assertThat(w.durationMinutes()).isEqualTo(w.slot().durationMinutes()));
    }

    @Test
    @DisplayName("a window keeps its slot identity when narrowed by a hard wall")
    void narrowing_preserves_the_slot_identity() {
        // Given
        DayWindow goal = window(DayTemplate.DEFAULT.resolve(DAY, BOGOTA, at(7, 0)), "GOAL_MORNING");

        // When: a hard commitment eats the first hour of the band.
        DayWindow narrowed = goal.narrowedTo(at(9, 30), goal.end());

        // Then
        assertThat(narrowed.slotId()).isEqualTo("GOAL_MORNING");
        assertThat(narrowed.durationMinutes()).isEqualTo(60);
        assertThat(narrowed.windowSize()).isEqualTo(WindowSize.STANDARD);
    }

    @Test
    @DisplayName("overlapping slots are rejected: the template is a partition, not a pile")
    void overlapping_slots_are_rejected() {
        // Given
        List<TemplateSlot> overlapping = List.of(
            new TemplateSlot("A", 0, 120, SlotPurpose.WORK),
            new TemplateSlot("B", 60, 180, SlotPurpose.GOAL));

        // Then
        assertThatThrownBy(() -> new DayTemplate(0, overlapping))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not overlap");
    }

    @Test
    @DisplayName("duplicate slot ids are rejected: the id is the block's identity anchor")
    void duplicate_slot_ids_are_rejected() {
        // Given
        List<TemplateSlot> duplicated = List.of(
            new TemplateSlot("A", 0, 60, SlotPurpose.WORK),
            new TemplateSlot("A", 60, 120, SlotPurpose.GOAL));

        // Then
        assertThatThrownBy(() -> new DayTemplate(0, duplicated))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate template slot id");
    }

    private static TemplateSlot slot(String id) {
        return DayTemplate.DEFAULT.slots().stream()
            .filter(s -> s.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static DayWindow window(List<DayWindow> windows, String slotId) {
        return windows.stream()
            .filter(w -> w.slotId().equals(slotId))
            .findFirst()
            .orElseThrow();
    }

    private static OffsetDateTime at(int hour, int minute) {
        return DAY.atTime(LocalTime.of(hour, minute)).atZone(BOGOTA).toOffsetDateTime();
    }
}
