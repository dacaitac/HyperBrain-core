package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shape of the day as a soft reference (ADR-040 D2): an ordered set of {@link TemplateSlot}s the
 * floor lays the windows against.
 *
 * <p><b>Why a reference at all.</b> The placement engine this replaces advanced a cursor from the start
 * of the day and returned the first free gap; with nothing acting as a wall it compressed every task
 * against the first hour, which is exactly the wall of twenty-one flat blocks that motivated ADR-040.
 * The template is what gives the day a form. That it is soft does not make it optional.
 *
 * <p><b>Why it is soft.</b> Two properties, both from D2:
 * <ul>
 *   <li><b>Variable start.</b> {@link #resolve} shifts every slot by the difference between the real
 *       wake instant and the template's own {@code wakeAnchorMinute}. Nothing is nailed to a wall
 *       clock — the {@code AGENDA} anchors are, but those are executables, not template rows.</li>
 *   <li><b>No target occupancy.</b> Unassigned gaps are admitted. There is no rule that fills the
 *       template, and none that validates the day against it.</li>
 * </ul>
 *
 * <p>The sanctioned {@link #DEFAULT} is the table in ADR-040; it is overridable per user from
 * {@code sys_user.settings.planner_constants.day_template}, so correcting which slots admit work is a
 * settings edit, never a deploy.
 *
 * @param wakeAnchorMinute the minute of day the template's own slots are drawn against — the reference
 *                         wake the real wake instant displaces
 * @param slots            the slots, in chronological order, non-overlapping; never null nor empty
 */
public record DayTemplate(int wakeAnchorMinute, List<TemplateSlot> slots) {

    private static final int MINUTES_PER_HOUR = 60;

    /**
     * The sanctioned template of ADR-040. Drawn against a 07:00 wake anchor: 06:00–07:00 is the margin
     * for winning against the bed, and by 07:00 one is up.
     */
    public static final DayTemplate DEFAULT = new DayTemplate(at(7, 0), List.of(
        new TemplateSlot("WAKE_MARGIN", at(6, 0), at(7, 0), SlotPurpose.WAKE_MARGIN),
        new TemplateSlot("PERSONAL_ROUTINE", at(7, 0), at(8, 0), SlotPurpose.PERSONAL_ROUTINE),
        new TemplateSlot("DAILY_STANDUP", at(8, 0), at(8, 20), SlotPurpose.AGENDA_ANCHOR),
        new TemplateSlot("MORNING_BUFFER", at(8, 20), at(8, 30), SlotPurpose.BUFFER),
        new TemplateSlot("GOAL_MORNING", at(8, 30), at(10, 30), SlotPurpose.GOAL),
        new TemplateSlot("LONG_BREAK", at(10, 30), at(11, 0), SlotPurpose.BREAK),
        new TemplateSlot("WORK_MORNING", at(11, 0), at(13, 0), SlotPurpose.WORK),
        new TemplateSlot("LUNCH", at(13, 0), at(14, 0), SlotPurpose.MEAL),
        new TemplateSlot("WHIRLWIND_LIGHT", at(14, 0), at(14, 30), SlotPurpose.WHIRLWIND),
        new TemplateSlot("FIXED_MEETING", at(14, 30), at(15, 30), SlotPurpose.AGENDA_ANCHOR),
        new TemplateSlot("MEETING_ZONE", at(15, 30), at(17, 0), SlotPurpose.MEETING_ZONE),
        new TemplateSlot("FREE_EVENING", at(17, 0), at(19, 0), SlotPurpose.FREE),
        new TemplateSlot("HOUSEHOLD", at(19, 0), at(21, 0), SlotPurpose.HOUSEHOLD),
        new TemplateSlot("WIND_DOWN", at(21, 0), at(22, 0), SlotPurpose.WIND_DOWN)));

    public DayTemplate {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("a day template must carry at least one slot");
        }
        List<TemplateSlot> ordered = new ArrayList<>(slots);
        ordered.sort(Comparator.comparingInt(TemplateSlot::startMinute));
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < ordered.size(); index++) {
            TemplateSlot slot = ordered.get(index);
            if (!ids.add(slot.id())) {
                throw new IllegalArgumentException("duplicate template slot id: " + slot.id());
            }
            if (index > 0 && slot.startMinute() < ordered.get(index - 1).endMinute()) {
                throw new IllegalArgumentException("template slots must not overlap: " + slot.id());
            }
        }
        slots = List.copyOf(ordered);
    }

    /**
     * Resolves the template onto a concrete day, displaced by the real wake instant.
     *
     * <p>The displacement is a single uniform shift of every slot — the day runs later as a whole, which
     * is precisely the "waking up late" case of ADR-040 D8: the same agenda slides, it is not rebuilt.
     * Slots pushed past midnight are kept: the arithmetic is on instants, and the caller clips against
     * the sleep frontier.
     *
     * @param targetDay the calendar day being planned; never null
     * @param zone      the user's timezone; never null
     * @param wake      the real wake instant the template rides on; never null
     * @return the concrete windows, chronologically ordered; never null, never empty
     */
    public List<DayWindow> resolve(LocalDate targetDay, ZoneId zone, OffsetDateTime wake) {
        if (targetDay == null || zone == null || wake == null) {
            throw new IllegalArgumentException("targetDay, zone and wake must not be null");
        }
        OffsetDateTime midnight = targetDay.atStartOfDay(zone).toOffsetDateTime();
        long wakeMinute = java.time.Duration.between(midnight, wake).toMinutes();
        long shift = wakeMinute - wakeAnchorMinute;
        List<DayWindow> windows = new ArrayList<>(slots.size());
        for (TemplateSlot slot : slots) {
            windows.add(new DayWindow(
                slot,
                midnight.plusMinutes(slot.startMinute() + shift),
                midnight.plusMinutes(slot.endMinute() + shift)));
        }
        return List.copyOf(windows);
    }

    private static int at(int hour, int minute) {
        return hour * MINUTES_PER_HOUR + minute;
    }
}
