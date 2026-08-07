package com.hyperbrain.planner.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * A {@link TemplateSlot} resolved onto concrete instants of the day being planned — <b>the unit of
 * scheduling</b> under ADR-040. Tasks go inside a window; the window is not derived from a task.
 *
 * <p>A window keeps its slot, so its identity survives the day: the block materialized from it carries
 * {@code template_slot_id = slot.id()}, which is what lets a regeneration recognise it (D7) and a
 * recurrence clone reuse it (D12) even after the LLM has renamed it and even when a hard commitment has
 * displaced it off its original hours.
 *
 * @param slot  the template slot this window realises; never null
 * @param start the concrete window start; never null
 * @param end   the concrete window end; never null, strictly after {@code start}
 */
public record DayWindow(TemplateSlot slot, OffsetDateTime start, OffsetDateTime end) {

    public DayWindow {
        if (slot == null) {
            throw new IllegalArgumentException("slot must not be null");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: " + start + " .. " + end);
        }
    }

    /** @return the window's slot key, the block's identity anchor */
    public String slotId() {
        return slot.id();
    }

    /** @return true when the floor may place admitted work in this window */
    public boolean schedulable() {
        return slot.schedulable();
    }

    /** @return the window duration in minutes */
    public int durationMinutes() {
        return (int) Duration.between(start, end).toMinutes();
    }

    /**
     * The size model of this window as materialized — derived from its <b>concrete</b> duration, not
     * from the template's, so a window clipped by a hard wall reports the split it actually yields.
     * Unsized purposes (the whirlwind above all) always report {@link WindowSize#UNSIZED}.
     *
     * @return the sanctioned size; never null
     */
    public WindowSize windowSize() {
        return slot.purpose().sized() ? WindowSize.of(durationMinutes()) : WindowSize.UNSIZED;
    }

    /**
     * Returns a copy of this window narrowed to a sub-interval, preserving the slot (and therefore the
     * block identity) — how a window survives being clipped by a hard wall.
     *
     * @param newStart the narrowed start; never null
     * @param newEnd   the narrowed end; never null, strictly after {@code newStart}
     * @return the narrowed window; never null
     */
    public DayWindow narrowedTo(OffsetDateTime newStart, OffsetDateTime newEnd) {
        return new DayWindow(slot, newStart, newEnd);
    }

    /** @return true when this window and {@code [from, to)} share at least one instant */
    public boolean overlaps(OffsetDateTime from, OffsetDateTime to) {
        return start.isBefore(to) && from.isBefore(end);
    }
}
