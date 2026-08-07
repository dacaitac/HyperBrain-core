package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.SlotPurpose;
import com.hyperbrain.planner.domain.model.TemplateSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DayWindowResolver — laying the day's windows (ADR-040)")
class DayWindowResolverTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 7);

    private final DayWindowResolver resolver = new DayWindowResolver(DayTemplate.DEFAULT);

    @Test
    @DisplayName("a normal day yields every band except the agenda ones")
    void a_normal_day_yields_every_non_agenda_band() {
        // When: up at the anchor hour, nothing in the way.
        List<DayWindow> windows = resolve(at(7, 0), at(7, 0), at(22, 0), List.of());

        // Then: the two agenda reflections are the only bands the generator will not fill.
        assertThat(windows).extracting(DayWindow::slotId)
            .doesNotContain("DAILY_STANDUP", "FIXED_MEETING")
            .contains("GOAL_MORNING", "WORK_MORNING", "HOUSEHOLD", "WIND_DOWN", "MEETING_ZONE");
    }

    @Test
    @DisplayName("waking late slides the windows and the lower bound trims what already went by")
    void a_late_wake_slides_the_day_and_trims_the_past() {
        // Given: up at 10:00 instead of 07:00 — three hours late.
        List<DayWindow> windows = resolve(at(10, 0), at(10, 0), at(22, 0), List.of());

        // Then: the goal band rides three hours later, and nothing opens before the real wake.
        assertThat(window(windows, "GOAL_MORNING").start()).isEqualTo(at(11, 30));
        assertThat(windows).allSatisfy(w -> assertThat(w.start()).isAfterOrEqualTo(at(10, 0)));
    }

    @Test
    @DisplayName("a replan from mid-morning keeps only what is still ahead, and clips the band it lands in")
    void a_replan_keeps_only_what_is_ahead() {
        // When: the run's lower bound is 09:30, inside the goal band.
        List<DayWindow> windows = resolve(at(7, 0), at(9, 30), at(22, 0), List.of());

        // Then: the goal band survives as its remaining hour and keeps its slot — the past is not
        // re-planned, but the band still serves the day.
        DayWindow goal = window(windows, "GOAL_MORNING");
        assertThat(goal.start()).isEqualTo(at(9, 30));
        assertThat(goal.end()).isEqualTo(at(10, 30));
        assertThat(windows).noneSatisfy(w -> assertThat(w.slotId()).isEqualTo("PERSONAL_ROUTINE"));
    }

    @Test
    @DisplayName("a hard commitment inside a band leaves the band's largest surviving stretch")
    void a_wall_leaves_the_largest_surviving_stretch() {
        // Given: a meeting from 08:30 to 09:00 — the first half hour of the two-hour goal band.
        OccupiedInterval meeting = new OccupiedInterval(UUID.randomUUID(), at(8, 30), at(9, 0), true);

        // When
        List<DayWindow> windows = resolve(at(7, 0), at(7, 0), at(22, 0), List.of(meeting));

        // Then: the band keeps its slot and yields its wider remainder (09:00–10:30), not the sliver
        // before the meeting. Its hours moved; its identity did not.
        DayWindow goal = window(windows, "GOAL_MORNING");
        assertThat(goal.start()).isEqualTo(at(9, 0));
        assertThat(goal.end()).isEqualTo(at(10, 30));
    }

    @Test
    @DisplayName("a band entirely spoken for simply yields no window — gaps are admitted")
    void a_fully_walled_band_yields_nothing() {
        // Given: a commitment covering the whole goal band.
        OccupiedInterval allDayMeeting =
            new OccupiedInterval(UUID.randomUUID(), at(8, 0), at(11, 0), true);

        // When
        List<DayWindow> windows = resolve(at(7, 0), at(7, 0), at(22, 0), List.of(allDayMeeting));

        // Then: nothing is invented to fill it. There is no target occupancy.
        assertThat(windows).extracting(DayWindow::slotId).doesNotContain("GOAL_MORNING");
    }

    @Test
    @DisplayName("a slot never yields two competing windows, even when a wall splits it in the middle")
    void a_split_band_still_yields_one_window() {
        // Given: a wall in the middle of the two-hour goal band, leaving 08:30–09:15 and 09:45–10:30.
        OccupiedInterval interruption =
            new OccupiedInterval(UUID.randomUUID(), at(9, 15), at(9, 45), false);

        // When
        List<DayWindow> windows = resolve(at(7, 0), at(7, 0), at(22, 0), List.of(interruption));

        // Then: exactly one window carries the slot, so identity can never be ambiguous.
        assertThat(windows).filteredOn(w -> w.slotId().equals("GOAL_MORNING")).hasSize(1);
    }

    @Test
    @DisplayName("a day whose whole frontier is spoken for yields nothing, and that is a legitimate day")
    void a_fully_occupied_day_yields_no_window() {
        OccupiedInterval wholeDay = new OccupiedInterval(UUID.randomUUID(), at(6, 0), at(23, 0), true);

        List<DayWindow> windows = resolve(at(7, 0), at(7, 0), at(22, 0), List.of(wholeDay));

        assertThat(windows).isEmpty();
    }

    @Test
    @DisplayName("an agenda band is never laid, even when it is completely free")
    void an_agenda_band_is_never_laid() {
        DayTemplate agendaOnly = new DayTemplate(7 * 60, List.of(
            new TemplateSlot("STANDUP", 8 * 60, 8 * 60 + 20, SlotPurpose.AGENDA_ANCHOR)));

        List<DayWindow> windows = new DayWindowResolver(agendaOnly)
            .resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0), List.of());

        assertThat(windows).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<DayWindow> resolve(OffsetDateTime wake, OffsetDateTime lowerBound,
                                    OffsetDateTime upperBound, List<OccupiedInterval> walls) {
        return resolver.resolve(DAY, BOGOTA, wake, lowerBound, upperBound, walls);
    }

    private static DayWindow window(List<DayWindow> windows, String slotId) {
        return windows.stream().filter(w -> w.slotId().equals(slotId)).findFirst().orElseThrow();
    }

    private static OffsetDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(BOGOTA).toOffsetDateTime();
    }
}
