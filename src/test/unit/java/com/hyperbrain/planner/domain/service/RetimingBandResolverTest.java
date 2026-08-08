package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MealWindow;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;
import com.hyperbrain.planner.domain.model.SlotPurpose;
import com.hyperbrain.planner.domain.model.TemplateSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RetimingBandResolver — the band each block may be retimed within")
class RetimingBandResolverTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 8);

    private final RetimingBandResolver resolver =
        new RetimingBandResolver(DayTemplate.DEFAULT, HumanizationSettings.DEFAULT);

    @Test
    @DisplayName("a band no meal sits in is its own hours: the meeting band is the afternoon, nothing more")
    void an_ordinary_band_is_its_own_hours() {
        // When: up at the template's anchor hour
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        // Then: «Reuniones» spans exactly 15:30–17:00 — the production case (a block moved to the
        // morning) is outside it, and no meal lands there to widen it.
        RetimingBand meetings = bands.get("MEETING_ZONE");
        assertThat(meetings.label()).isEqualTo("Reuniones");
        assertThat(meetings.start()).isEqualTo(at(15, 30));
        assertThat(meetings.end()).isEqualTo(at(17, 0));
        assertThat(meetings.contains(at(7, 0), at(9, 0))).isFalse();
    }

    @Test
    @DisplayName("a band rides the real wake, like the window it bounds")
    void a_band_rides_the_real_wake() {
        // When: up at 10:00, three hours after the template's anchor
        Map<String, RetimingBand> bands = resolve(at(10, 0), at(10, 0), at(23, 0));

        // Then: the work band slides with the day instead of demanding the same hour every morning
        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(14, 0));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(16, 0));
    }

    @Test
    @DisplayName("the template rides the wake but a meal does not, so it widens whatever band it lands in")
    void a_meal_stays_on_the_wall_clock_when_the_day_slides() {
        // Given: up at 10:00, so «Meta de la mañana» runs 11:30–13:30 — while lunch stays at 12:30, its
        // civil hour (it is a human anchor, not an instant that slides). It lands squarely in the goal
        // band, which is therefore the band it widens.
        Map<String, RetimingBand> bands = resolve(at(10, 0), at(10, 0), at(23, 0));

        // Then: the goal block may float across the plausible lunch hours, which is honest — the meal
        // wall splits that window anyway — and «Almuerzo», now at 16:00–17:00, holds no meal at all.
        assertThat(bands.get("GOAL_MORNING").start()).isEqualTo(at(11, 30));
        assertThat(bands.get("GOAL_MORNING").end()).isEqualTo(at(14, 30));
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(16, 0));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(17, 0));
    }

    @Test
    @DisplayName("a meal band is widened to the meal's plausible hours, never narrowed")
    void a_meal_band_is_widened_to_the_plausible_hours() {
        // Given: the LUNCH band is 13:00–14:00 and the lunch anchor (12:30–13:30) sits in it, plausible
        // between 11:30 and 14:30.
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        // Then: the meal may float across its plausible hours — and the band still covers its own
        // window, so the floor's own placement can never fall outside it.
        RetimingBand lunch = bands.get("LUNCH");
        assertThat(lunch.start()).isEqualTo(at(11, 30));
        assertThat(lunch.end()).isEqualTo(at(14, 30));
        assertThat(lunch.contains(at(13, 0), at(14, 0))).isTrue();
        assertThat(lunch.contains(at(15, 0), at(15, 30))).isFalse();
    }

    @Test
    @DisplayName("a band no meal sits in stays its own hours, MEAL slot or not")
    void a_band_no_meal_sits_in_is_not_widened() {
        // Given: a user who configured no meals at all
        RetimingBandResolver bare =
            new RetimingBandResolver(DayTemplate.DEFAULT, HumanizationSettings.NO_OP);

        // When
        RetimingBand lunch = bare.resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0)).get("LUNCH");

        // Then: no widening was invented for it
        assertThat(lunch.start()).isEqualTo(at(13, 0));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("a meal is matched to the band it sits in, never by name")
    void a_meal_is_matched_by_overlap() {
        // Given: a meal labelled nothing like the band, but landing inside the LUNCH band's hours
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("comida", LocalTime.of(13, 15), LocalTime.of(13, 45),
                LocalTime.of(12, 0), LocalTime.of(15, 0))),
            0.10);

        // When
        RetimingBand lunch = new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0)).get("LUNCH");

        // Then: moving either one in configuration keeps them paired
        assertThat(lunch.start()).isEqualTo(at(12, 0));
        assertThat(lunch.end()).isEqualTo(at(15, 0));
    }

    @Test
    @DisplayName("every band is clamped to the planning bounds, so none authorizes a move into the past")
    void bands_are_clamped_to_the_planning_bounds() {
        // When: a replan at 14:00 on a day whose bedtime is 22:00
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(14, 0), at(22, 0));

        // Then: the morning bands are gone entirely and the band the replan lands in starts at now
        assertThat(bands).doesNotContainKeys("GOAL_MORNING", "WORK_MORNING");
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(14, 0));
        assertThat(bands.get("WIND_DOWN").end()).isEqualTo(at(22, 0));
    }

    @Test
    @DisplayName("the agenda reflections carry a band too — a block born in one is bounded like any other")
    void agenda_reflections_carry_a_band() {
        // The resolver bounds movement; deciding what the floor may fill is the window resolver's job,
        // and the two must not be confused.
        assertThat(resolve(at(7, 0), at(7, 0), at(22, 0))).containsKey("DAILY_STANDUP");
    }

    @Test
    @DisplayName("a meal narrower than the band it sits in never narrows it — the widening only widens")
    void a_narrower_meal_never_narrows_its_band() {
        // Given: a meal whose plausible band (13:15–13:45) is strictly INSIDE the LUNCH band
        // (13:00–14:00). Taking it as the band would leave the floor's own window — the whole
        // 13:00–14:00 slot — outside its band, and every day would degrade over a placement the model
        // never made. This is the load-bearing half of "widen, never narrow".
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 15), LocalTime.of(13, 45),
                LocalTime.of(13, 15), LocalTime.of(13, 45))),
            0.10);

        // When
        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        // Then: the slot's own hours survive whole
        assertThat(lunch.start()).isEqualTo(at(13, 0));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
        assertThat(lunch.contains(at(13, 0), at(14, 0))).isTrue();
    }

    @Test
    @DisplayName("a meal that reaches out on one side only widens that side")
    void a_one_sided_meal_widens_one_side_only() {
        // Given: lunch may start as early as 11:30 but never run past 13:45 — earlier than the LUNCH
        // band's own 14:00 end.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(13, 45),
                LocalTime.of(11, 30), LocalTime.of(13, 45))),
            0.10);

        // When
        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        // Then: the union of both, edge by edge
        assertThat(lunch.start()).isEqualTo(at(11, 30));
        assertThat(lunch.end()).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("a meal straddling two bands widens only the one it sits in, never its neighbour")
    void a_meal_crossing_a_band_edge_leaves_the_neighbour_untouched() {
        // Given: the sanctioned lunch anchor (12:30–13:30) straddles WORK_MORNING (11:00–13:00) and
        // LUNCH (13:00–14:00) — half an hour in each, an exact tie, which the half-open convention
        // hands to the later band.
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        // Then: «Oficio» stays exactly the work band — a work block may not slide into the afternoon
        // just because the meal it borders may.
        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(11, 0));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(13, 0));
        // And the band the meal sits in did widen, so the two are genuinely judged apart.
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(11, 30));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(14, 30));
    }

    @Test
    @DisplayName("a meal mostly inside one band widens that one, and only that one")
    void a_meal_mostly_inside_one_band_widens_that_one() {
        // Given: a meal that spends 40 minutes in WORK_MORNING (11:00–13:00) and 20 in LUNCH — no tie
        // this time, so the band holding most of it is the one that inherits its plausible hours.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(12, 20), LocalTime.of(13, 20),
                LocalTime.of(11, 30), LocalTime.of(14, 30))),
            0.10);

        Map<String, RetimingBand> bands = bandsOf(settings);

        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(11, 0));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(14, 30));
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(13, 0));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(14, 0));
    }

    @Test
    @DisplayName("a meal spanning three bands widens the middle one — the widest share, not the last touched")
    void a_meal_spanning_three_bands_widens_the_middle_one() {
        // Given: a long meal from 12:45 to 14:15 — fifteen minutes in «Oficio» (11:00–13:00), a full
        // hour in «Almuerzo» (13:00–14:00) and fifteen in «Torbellino» (14:00–14:30). Chronology only
        // ever breaks a tie; the share decides first, so the later band must NOT win here. Without
        // this, an implementation that simply kept the last band it touched would pass every other
        // test in this class.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("comida larga", LocalTime.of(12, 45), LocalTime.of(14, 15),
                LocalTime.of(11, 0), LocalTime.of(15, 0))),
            0.10);

        Map<String, RetimingBand> bands = bandsOf(settings);

        assertThat(bands.get("LUNCH").start()).isEqualTo(at(11, 0));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(15, 0));
        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(11, 0));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(13, 0));
        assertThat(bands.get("WHIRLWIND_LIGHT").start()).isEqualTo(at(14, 0));
        assertThat(bands.get("WHIRLWIND_LIGHT").end()).isEqualTo(at(14, 30));
    }

    @Test
    @DisplayName("the tie is decided by chronology, not by the order the template lists its slots")
    void the_tie_is_not_decided_by_the_template_listing_order() {
        // The sanctioned lunch splits exactly between «Oficio» and «Almuerzo», and the convention hands
        // an even split to the later band. "Later" must mean later in the day and not later in whatever
        // order somebody happened to write the slots in settings — the template is user-editable, so a
        // resolution that leaned on the input order would make the day's tolerance depend on the shape
        // of a JSON document. Feeding the very same slots backwards must change nothing at all.
        List<TemplateSlot> reversed = new ArrayList<>(DayTemplate.DEFAULT.slots());
        Collections.reverse(reversed);
        DayTemplate shuffled = new DayTemplate(DayTemplate.DEFAULT.wakeAnchorMinute(), reversed);

        Map<String, RetimingBand> fromShuffled =
            new RetimingBandResolver(shuffled, HumanizationSettings.DEFAULT)
                .resolve(DAY, BOGOTA, at(7, 0), at(5, 0), at(23, 0));

        assertThat(fromShuffled).usingRecursiveComparison()
            .isEqualTo(resolver.resolve(DAY, BOGOTA, at(7, 0), at(5, 0), at(23, 0)));
        // And the tie itself still lands where the convention says it does: on the later band.
        assertThat(fromShuffled.get("LUNCH").end()).isEqualTo(at(14, 30));
        assertThat(fromShuffled.get("WORK_MORNING").end()).isEqualTo(at(13, 0));
    }

    @Test
    @DisplayName("the union of two meals in one band does not depend on the order they were configured")
    void the_union_does_not_depend_on_the_meal_order() {
        // A union is commutative and the resolution must be too: the same two meals declared in either
        // order are the same statement about the day. Nothing here may depend on which meal the
        // configuration happens to list first.
        MealWindow lunch = new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(13, 30),
            LocalTime.of(12, 0), LocalTime.of(14, 30));
        MealWindow snack = new MealWindow("snack", LocalTime.of(13, 30), LocalTime.of(14, 0),
            LocalTime.of(11, 0), LocalTime.of(17, 0));

        Map<String, RetimingBand> asConfigured =
            bandsOf(new HumanizationSettings(List.of(lunch, snack), 0.10));
        Map<String, RetimingBand> reversed =
            bandsOf(new HumanizationSettings(List.of(snack, lunch), 0.10));

        assertThat(reversed).usingRecursiveComparison().isEqualTo(asConfigured);
        assertThat(reversed.get("LUNCH").start()).isEqualTo(at(11, 0));
        assertThat(reversed.get("LUNCH").end()).isEqualTo(at(17, 0));
    }

    @Test
    @DisplayName("a widened band keeps its slot's name — a meal lends its hours, never its label")
    void a_widened_band_keeps_its_slots_name() {
        // The label is not decoration. It is rendered to the model as the band's name and it is what
        // the degraded floor titles the block with, so a widening that carried the meal's label would
        // put «lunch» on the calendar where «Almuerzo» belongs — the machine-output day ADR-040 exists
        // to undo. All three sanctioned meals widen a band here; none of them may rename it.
        Map<String, RetimingBand> bands = resolver.resolve(DAY, BOGOTA, at(7, 0), at(5, 0), at(23, 0));

        assertThat(bands.get("PERSONAL_ROUTINE").label()).isEqualTo("Rutina personal");
        assertThat(bands.get("LUNCH").label()).isEqualTo("Almuerzo");
        assertThat(bands.get("HOUSEHOLD").label()).isEqualTo("Casa");
    }

    @Test
    @DisplayName("a meal widens the band it sits in whatever that band is for, not only a MEAL slot")
    void a_meal_anchored_in_an_ordinary_band_widens_it() {
        // Under the sanctioned template only lunch has a band of its own: breakfast (07:00–07:30,
        // plausible 05:30–10:00) sits inside «Rutina personal» and dinner (19:00–20:00, plausible
        // 18:00–21:30) inside «Casa». Keying the widening on the slot's purpose left the configured
        // hours of two meals out of three inert — the defect this pins.
        // Bounds wide enough to show the widening itself, before the run's own clamping.
        Map<String, RetimingBand> bands =
            resolver.resolve(DAY, BOGOTA, at(7, 0), at(5, 0), at(23, 0));

        assertThat(bands.get("PERSONAL_ROUTINE").start()).isEqualTo(at(5, 30));
        assertThat(bands.get("PERSONAL_ROUTINE").end()).isEqualTo(at(10, 0));
        assertThat(bands.get("HOUSEHOLD").start()).isEqualTo(at(18, 0));
        assertThat(bands.get("HOUSEHOLD").end()).isEqualTo(at(21, 30));
        // And widening «Casa» to the dinner hours does not forgive the production defect: the
        // household block moved to seven in the morning is still outside its band.
        assertThat(bands.get("HOUSEHOLD").contains(at(7, 0), at(9, 0))).isFalse();
    }

    @Test
    @DisplayName("a widened ordinary band is clamped like any other: the morning band starts at the wake")
    void a_widened_ordinary_band_is_clamped_to_the_bounds() {
        // What the real morning run leaves of the two bands above: breakfast may plausibly start at
        // 05:30, but this run plans from the wake, so «Rutina personal» opens there and not before.
        Map<String, RetimingBand> bands = resolve(at(7, 0), at(7, 0), at(22, 0));

        assertThat(bands.get("PERSONAL_ROUTINE").start()).isEqualTo(at(7, 0));
        assertThat(bands.get("PERSONAL_ROUTINE").end()).isEqualTo(at(10, 0));
        assertThat(bands.get("HOUSEHOLD").start()).isEqualTo(at(18, 0));
        assertThat(bands.get("HOUSEHOLD").end()).isEqualTo(at(21, 30));
    }

    @Test
    @DisplayName("a meal merely touching a band's edge does not widen it — the overlap is half-open")
    void a_meal_touching_the_edge_does_not_widen() {
        // Given: a meal ending exactly when the LUNCH band opens. Sharing an instant is not sitting in
        // the band, so nothing about that meal may bound a block born in it.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("brunch", LocalTime.of(12, 0), LocalTime.of(13, 0),
                LocalTime.of(10, 0), LocalTime.of(13, 0))),
            0.10);

        Map<String, RetimingBand> bands = bandsOf(settings);

        assertThat(bands.get("LUNCH").start()).isEqualTo(at(13, 0));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(14, 0));
        // The band it does sit in — the whole meal is inside «Oficio» — took the widening instead.
        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(10, 0));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(13, 0));
    }

    @Test
    @DisplayName("a meal that falls outside the template altogether widens nothing")
    void a_meal_outside_the_template_widens_nothing() {
        // A meal at three in the morning sits in no band of the day: the template opens at 06:00. The
        // resolver must leave the day exactly as it found it rather than attach the hours to the
        // nearest slot, which would hand a two-hour tolerance to a band no meal ever touches. The
        // whole map is compared against the no-meal resolution, so a widening leaking anywhere fails.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("madrugada", LocalTime.of(3, 0), LocalTime.of(3, 30),
                LocalTime.of(2, 0), LocalTime.of(4, 0))),
            0.10);

        assertThat(bandsOf(settings)).usingRecursiveComparison()
            .isEqualTo(new RetimingBandResolver(DayTemplate.DEFAULT, HumanizationSettings.NO_OP)
                .resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0)));
    }

    @Test
    @DisplayName("a meal falling in a gap of the template widens neither of its neighbours")
    void a_meal_in_a_gap_widens_neither_neighbour() {
        // The template admits gaps (ADR-040 D2: no target occupancy, nothing fills the day), and a gap
        // is not a band. A meal landing in one sits in nothing, so nothing is widened — least of all
        // the slot that merely happens to be nearest, which is the tempting wrong answer.
        DayTemplate gapped = new DayTemplate(480, List.of(
            new TemplateSlot("MORNING", "Mañana", 480, 540, SlotPurpose.WORK),
            new TemplateSlot("AFTERNOON", "Tarde", 840, 900, SlotPurpose.WORK)));
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("brunch", LocalTime.of(11, 0), LocalTime.of(12, 0),
                LocalTime.of(10, 0), LocalTime.of(13, 0))),
            0.10);

        Map<String, RetimingBand> bands = new RetimingBandResolver(gapped, settings)
            .resolve(DAY, BOGOTA, at(8, 0), at(7, 0), at(22, 0));

        assertThat(bands.get("MORNING").start()).isEqualTo(at(8, 0));
        assertThat(bands.get("MORNING").end()).isEqualTo(at(9, 0));
        assertThat(bands.get("AFTERNOON").start()).isEqualTo(at(14, 0));
        assertThat(bands.get("AFTERNOON").end()).isEqualTo(at(15, 0));
    }

    @Test
    @DisplayName("an hour-early wake hands the lunch hours to «Torbellino» and the breakfast hours to «Daily»")
    void an_hour_early_wake_hands_the_meals_to_other_bands() {
        // The visible consequence of pairing by overlap on a displaced day. Meals are civil hours and
        // the template rides the real wake, so getting up an hour early slides every band an hour
        // under fixed meals: the sanctioned lunch (12:30–13:30) then splits exactly between «Almuerzo»
        // (now 12:00–13:00) and «Torbellino» (now 13:00–13:30), and the even split goes to the later
        // band. The band named for the meal keeps no tolerance at all and the whirlwind gains three
        // hours of it. Characterized, not endorsed — it follows from the rule exactly as decided.
        Map<String, RetimingBand> bands = resolver.resolve(DAY, BOGOTA, at(6, 0), at(5, 0), at(23, 0));

        assertThat(bands.get("LUNCH").start()).isEqualTo(at(12, 0));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(13, 0));
        assertThat(bands.get("WHIRLWIND_LIGHT").start()).isEqualTo(at(11, 30));
        assertThat(bands.get("WHIRLWIND_LIGHT").end()).isEqualTo(at(14, 30));
        // And breakfast (07:00–07:30) now shares 20 minutes with «Daily» against 10 with «Respiro»,
        // so «Rutina personal» — the band it sits in on an ordinary day — is left untouched.
        assertThat(bands.get("PERSONAL_ROUTINE").start()).isEqualTo(at(6, 0));
        assertThat(bands.get("PERSONAL_ROUTINE").end()).isEqualTo(at(7, 0));
        assertThat(bands.get("DAILY_STANDUP").start()).isEqualTo(at(5, 30));
        assertThat(bands.get("DAILY_STANDUP").end()).isEqualTo(at(10, 0));
    }

    @Test
    @DisplayName("on a displaced day a widening can land on a band that yields no block, and reach nothing")
    void a_widening_can_land_on_a_band_that_yields_no_block() {
        // The same hour-early day, now read against the windows the floor actually lays. «Daily» is an
        // AGENDA_ANCHOR — never schedulable, so it can never yield a block — and «Torbellino» is
        // entirely eaten by the very lunch wall that widened it. Both widenings therefore bound
        // nothing, while the two bands that do carry a block that day («Rutina personal» and
        // «Almuerzo») keep exactly zero tolerance. This is the same class of inert configuration the
        // purpose-keyed widening was fixed for, resurfacing on a displaced day: pinned as the current
        // behaviour, not as the desired one.
        List<OccupiedInterval> mealWalls = HumanizationSettings.DEFAULT.mealWindows().stream()
            .map(meal -> meal.toWall(DAY, BOGOTA))
            .toList();
        List<DayWindow> laid = new DayWindowResolver(DayTemplate.DEFAULT)
            .resolve(DAY, BOGOTA, at(6, 0), at(5, 0), at(23, 0), mealWalls);

        assertThat(laid.stream().map(DayWindow::slotId).toList())
            .doesNotContain("DAILY_STANDUP", "WHIRLWIND_LIGHT")
            .contains("PERSONAL_ROUTINE", "LUNCH");
    }

    @Test
    @DisplayName("a widened band is still clamped: nothing floats past bedtime")
    void a_widened_band_is_still_clamped_to_the_bounds() {
        // Given: a late meal band that reaches past the bedtime edge of this run.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(14, 0),
                LocalTime.of(10, 0), LocalTime.of(23, 30))),
            0.10);

        RetimingBand lunch = new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, at(7, 0), at(11, 0), at(22, 0)).get("LUNCH");

        // Then: the plausible hours widen it, and the run's bounds close it again on both sides.
        assertThat(lunch.start()).isEqualTo(at(11, 0));
        assertThat(lunch.end()).isEqualTo(at(22, 0));
    }

    @Test
    @DisplayName("when two meals sit in the same band, their plausible hours are unioned, not raced")
    void two_meals_in_one_band_union_their_plausible_hours() {
        // Reachable now that any band can hold a meal. Honouring only the first configured meal would
        // leave the band narrower than the second meal's own hours — a band one of its own meals could
        // be pushed out of — so the only reading consistent with "widen, never narrow" is the union.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(
                new MealWindow("lunch", LocalTime.of(13, 0), LocalTime.of(13, 30),
                    LocalTime.of(12, 0), LocalTime.of(14, 30)),
                new MealWindow("snack", LocalTime.of(13, 30), LocalTime.of(14, 0),
                    LocalTime.of(11, 0), LocalTime.of(17, 0))),
            0.10);

        RetimingBand lunch = bandsOf(settings).get("LUNCH");

        assertThat(lunch.start()).isEqualTo(at(11, 0));
        assertThat(lunch.end()).isEqualTo(at(17, 0));
        assertThat(lunch.contains(at(12, 0), at(14, 30))).isTrue();
        assertThat(lunch.contains(at(11, 0), at(17, 0))).isTrue();
    }

    @Test
    @DisplayName("two meals sitting in two different bands each widen their own, and only their own")
    void two_meals_in_two_bands_stay_apart() {
        // The union is per band, never across the day: a band inherits the hours of the meals that sit
        // in it and nothing from the meals that do not.
        HumanizationSettings settings = new HumanizationSettings(
            List.of(
                new MealWindow("elevenses", LocalTime.of(11, 30), LocalTime.of(12, 0),
                    LocalTime.of(10, 45), LocalTime.of(12, 30)),
                new MealWindow("lunch", LocalTime.of(13, 15), LocalTime.of(13, 45),
                    LocalTime.of(12, 45), LocalTime.of(15, 30))),
            0.10);

        Map<String, RetimingBand> bands = bandsOf(settings);

        assertThat(bands.get("WORK_MORNING").start()).isEqualTo(at(10, 45));
        assertThat(bands.get("WORK_MORNING").end()).isEqualTo(at(13, 0));
        assertThat(bands.get("LUNCH").start()).isEqualTo(at(12, 45));
        assertThat(bands.get("LUNCH").end()).isEqualTo(at(15, 30));
    }

    @Test
    @DisplayName("every band contains the template band it was drawn from — the widening is a union")
    void every_band_contains_its_template_band() {
        // The invariant the guard leans on, checked against the whole sanctioned template at once and
        // with a pathological meal configuration: a band may only ever be as wide as, or wider than,
        // the slot it names — clipped by nothing but the run's own bounds.
        HumanizationSettings mean = new HumanizationSettings(
            List.of(new MealWindow("lunch", LocalTime.of(13, 10), LocalTime.of(13, 20),
                LocalTime.of(13, 10), LocalTime.of(13, 20))),
            0.10);
        // Bounds wide enough to hold the whole template (06:00–22:00), so the clamping — a legitimate
        // narrowing, and the only one — cannot be mistaken for the widening drifting.
        Map<String, RetimingBand> bands = new RetimingBandResolver(DayTemplate.DEFAULT, mean)
            .resolve(DAY, BOGOTA, at(7, 0), at(6, 0), at(22, 0));

        for (DayWindow window : DayTemplate.DEFAULT.resolve(DAY, BOGOTA, at(7, 0))) {
            assertThat(bands.get(window.slotId()))
                .as("band of %s", window.slotId())
                .isNotNull()
                .satisfies(band -> assertThat(band.contains(window.start(), window.end())).isTrue());
        }
    }

    @Test
    @DisplayName("the same day resolves to the same bands twice, and the map is immutable")
    void the_resolution_is_deterministic_and_immutable() {
        Map<String, RetimingBand> first = resolve(at(7, 0), at(7, 0), at(22, 0));
        Map<String, RetimingBand> second = resolve(at(7, 0), at(7, 0), at(22, 0));

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
        assertThatThrownBy(() -> first.put("X", first.get("LUNCH")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a null template, null settings and any null argument of a resolution")
    void rejects_null_inputs() {
        assertThatThrownBy(() -> new RetimingBandResolver(null, HumanizationSettings.DEFAULT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetimingBandResolver(DayTemplate.DEFAULT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(null, BOGOTA, at(7, 0), at(7, 0), at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, null, at(7, 0), at(7, 0), at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, BOGOTA, null, at(7, 0), at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, BOGOTA, at(7, 0), null, at(22, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(DAY, BOGOTA, at(7, 0), at(7, 0), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, RetimingBand> bandsOf(HumanizationSettings settings) {
        return new RetimingBandResolver(DayTemplate.DEFAULT, settings)
            .resolve(DAY, BOGOTA, at(7, 0), at(7, 0), at(22, 0));
    }

    private Map<String, RetimingBand> resolve(OffsetDateTime wake, OffsetDateTime lowerBound,
                                              OffsetDateTime upperBound) {
        return resolver.resolve(DAY, BOGOTA, wake, lowerBound, upperBound);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(DAY, LocalTime.of(hour, minute), ZoneOffset.ofHours(-5));
    }
}
