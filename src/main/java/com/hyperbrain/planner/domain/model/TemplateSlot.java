package com.hyperbrain.planner.domain.model;

/**
 * One slot of the day template (ADR-040, «La plantilla del día»): a named band of the day with a
 * purpose, expressed as <b>minutes of day relative to the template's own wake anchor</b> — never as an
 * absolute wall-clock time.
 *
 * <p>That relativity is the whole point of ADR-040 D2: nobody gets up at the same hour every day, and a
 * template that demands it is a template that gets abandoned. The slots ride on the real wake instant;
 * only the {@code AGENDA} anchors keep an absolute hour, and they are executables, not template rows.
 *
 * <p><b>{@code id} is the block's identity anchor.</b> It is the value persisted on
 * {@code core_executable.template_slot_id} (ADR-040 D14, the single schema change) and the key the
 * regeneration reconciles on (D7) and the recurrence clone reuses (D12). It is derived from the
 * template and never from the block's name, its user or its membership — precisely so that the LLM
 * renaming a block cannot duplicate its calendar event.
 *
 * @param id      the stable slot key; never null nor blank
 * @param startMinute the slot start, in minutes from midnight of the template's reference day
 * @param endMinute   the slot end, in minutes from midnight; strictly after {@code startMinute}
 * @param purpose the slot's destination; never null
 */
public record TemplateSlot(String id, int startMinute, int endMinute, SlotPurpose purpose) {

    public TemplateSlot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("slot id must not be blank");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("purpose must not be null");
        }
        if (startMinute < 0) {
            throw new IllegalArgumentException("startMinute must not be negative: " + startMinute);
        }
        if (endMinute <= startMinute) {
            throw new IllegalArgumentException(
                "endMinute must be after startMinute: " + startMinute + " .. " + endMinute);
        }
        id = id.strip();
    }

    /** @return the slot duration in minutes */
    public int durationMinutes() {
        return endMinute - startMinute;
    }

    /** @return true when the deterministic floor may place admitted work here */
    public boolean schedulable() {
        return purpose.schedulable();
    }

    /**
     * The window size this slot renders as. Only the sized purposes (goal and work) carry one; the
     * whirlwind and every other purpose are {@link WindowSize#UNSIZED} by decision.
     *
     * @return the sanctioned size; never null
     */
    public WindowSize windowSize() {
        return purpose.sized() ? WindowSize.of(durationMinutes()) : WindowSize.UNSIZED;
    }
}
