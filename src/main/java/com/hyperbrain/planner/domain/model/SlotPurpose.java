package com.hyperbrain.planner.domain.model;

/**
 * What a slot of the day template is for, and — derived from it — whether the deterministic floor may
 * place work there (ADR-040, «La plantilla del día»).
 *
 * <p>The template is a <b>soft reference</b>, not a contract: it exists so the day has a shape to be
 * laid against, because a placement engine with no reference structure collapses the whole day onto the
 * first free hour (ADR-040 D2). Two consequences are encoded here and nowhere else:
 *
 * <ul>
 *   <li><b>Not every slot admits work.</b> The wake margin, the buffers, the meals, the long break and
 *       the wind-down are the day's breathing room; the meeting zone is held for commitments the user
 *       does not author through this system; the household band is <em>vetoed by decision</em>
 *       (ADR-040 D19) so the system can never learn that the night is spill-over capacity.</li>
 *   <li><b>The window sizes only govern goals and work.</b> The whirlwind is explicitly exempt
 *       (ADR-040, «Los tamaños se aplican a las metas y al trabajo, no al torbellino»): a whirlwind
 *       block may last whatever it has to, which is what makes the template's non-multiple slots
 *       harmless.</li>
 * </ul>
 *
 * <p>The {@code AGENDA_ANCHOR} purposes are <b>reflections, not configuration</b>: the real anchors are
 * {@code AGENDA} executables in the inventory (ADR-040 D9), and they wall on their own. The template
 * merely echoes them so the shape of the day reads whole.
 */
public enum SlotPurpose {

    /** 06:00–07:00 — the margin for winning against the bed. Nothing is ever scheduled here. */
    WAKE_MARGIN(false, false),

    /** 07:00–08:00 — the personal routine ("WakeUp"): bed, fruit, getting ready. Work goes here. */
    PERSONAL_ROUTINE(true, false),

    /** A reflection of an {@code AGENDA} task that anchors the day; the task itself is the wall. */
    AGENDA_ANCHOR(false, false),

    /** A short cushion between two commitments. Never scheduled. */
    BUFFER(false, false),

    /** A window dedicated to a crucially important goal. Sized (ADR-040, «Los tres tamaños»). */
    GOAL(true, true),

    /** A long break. Never scheduled. */
    BREAK(false, false),

    /** A window dedicated to work. Sized. */
    WORK(true, true),

    /** A protected meal window. Never scheduled. */
    MEAL(false, false),

    /** Light whirlwind. Schedulable, and deliberately <b>not</b> sized. */
    WHIRLWIND(true, false),

    /** The zone held for occasional meetings; the day's own {@code AGENDA} rows land here. */
    MEETING_ZONE(false, false),

    /**
     * The band that is usually free once work ends — personal whirlwind or a goal, indifferently, with
     * no conditions and no quota. Schedulable, unsized.
     */
    FREE(true, false),

    /**
     * Eating, home, flat, self-care — <b>vetoed to the generator by decision</b> (ADR-040 D19), not by
     * "not yet". Overflow leaves through the exclusion list, in plain sight.
     */
    HOUSEHOLD(false, false),

    /** The wind-down towards sleep. Never scheduled. */
    WIND_DOWN(false, false);

    private final boolean schedulable;
    private final boolean sized;

    SlotPurpose(boolean schedulable, boolean sized) {
        this.schedulable = schedulable;
        this.sized = sized;
    }

    /** @return true when the deterministic floor may place admitted work in a slot of this purpose */
    public boolean schedulable() {
        return schedulable;
    }

    /**
     * @return true when the window-size model (and therefore its internal distribution) applies —
     *         goals and work only; the whirlwind is exempt by decision
     */
    public boolean sized() {
        return sized;
    }
}
