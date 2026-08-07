package com.hyperbrain.planner.domain.model;

/**
 * What a slot of the day template is for, and — derived from it — whether the deterministic floor may
 * place work there (ADR-040, «La plantilla del día»).
 *
 * <p>The template is a <b>soft reference</b>, not a contract: it exists so the day has a shape to be
 * laid against, because a placement engine with no reference structure collapses the whole day onto the
 * first free hour (ADR-040 D2).
 *
 * <p><b>Only the agenda blocks placement</b> (Daniel, 2026-08-07 — a directive that supersedes the ADR
 * on this point). The generator may lay work in every band of the day; the single thing it may not
 * write over is time already spoken for by an {@code AGENDA} element. The user is under no such
 * restriction — he can put whatever he wants wherever he wants, and the planner does not undo it.
 *
 * <p>Two things the ADR had settled are annulled by that directive, and they are named here so nobody
 * re-introduces them by reading the document alone:
 * <ul>
 *   <li>the <b>19:00–21:00 household band is no longer vetoed</b> (ADR-040 D19). The veto was a policy
 *       argument about a reinforcement loop, not a technical constraint, so nothing else depended on
 *       it;</li>
 *   <li>the <b>cushion after the daily stand-up is no longer protected</b>. It was pure shape; the
 *       five-minute inter-block buffer it is often confused with is a different mechanism, and ADR-040
 *       D1 had already retired that one.</li>
 * </ul>
 *
 * <p>What still limits the day is the time axis alone: the sleep frontier, the {@code AGENDA} walls,
 * the blocks that already hold time, and the protected meal anchors. Nothing trims the tail of the day.
 *
 * <p>The window sizes, by contrast, still only govern goals and work: the whirlwind is explicitly
 * exempt (ADR-040, «Los tamaños se aplican a las metas y al trabajo, no al torbellino»), which is what
 * makes the template's slots that are not a multiple of any size harmless.
 *
 * <p>The {@code AGENDA_ANCHOR} purposes are <b>reflections, not configuration</b>: the real anchors are
 * {@code AGENDA} executables in the inventory (ADR-040 D9), and they wall on their own. The template
 * merely echoes them so the shape of the day reads whole — and, being agenda, they are the one purpose
 * the generator will not fill.
 */
public enum SlotPurpose {

    /**
     * 06:00–07:00 — the margin for winning against the bed. Placeable in principle, but it sits below
     * the sleep frontier's wake edge, so the planning window excludes it on its own: the temporal axis
     * does the work, not a policy flag.
     */
    WAKE_MARGIN(true, false),

    /** 07:00–08:00 — the personal routine ("WakeUp"): bed, fruit, getting ready. Work goes here. */
    PERSONAL_ROUTINE(true, false),

    /** A reflection of an {@code AGENDA} task that anchors the day; the task itself is the wall. */
    AGENDA_ANCHOR(false, false),

    /** A short cushion between two commitments. Placeable since Daniel's 2026-08-07 directive. */
    BUFFER(true, false),

    /** A window dedicated to a crucially important goal. Sized (ADR-040, «Los tres tamaños»). */
    GOAL(true, true),

    /** A long break. Placeable since Daniel's 2026-08-07 directive. */
    BREAK(true, false),

    /** A window dedicated to work. Sized. */
    WORK(true, true),

    /**
     * A meal window. Placeable as a slot, yet in practice still protected: the meal anchors enter the
     * run as walls of their own, and a window is clipped by every wall it meets. The protection lives
     * on the time axis, where ADR-040 D1 says availability belongs — not in a slot flag.
     */
    MEAL(true, false),

    /** Light whirlwind. Schedulable, and deliberately <b>not</b> sized. */
    WHIRLWIND(true, false),

    /**
     * The zone where occasional meetings tend to land. Placeable: whatever meeting actually
     * materialises arrives as an {@code AGENDA} executable and walls the time it really takes, so
     * reserving the whole band in advance would give away ninety minutes a day for nothing.
     */
    MEETING_ZONE(true, false),

    /**
     * The band that is usually free once work ends — personal whirlwind or a goal, indifferently, with
     * no conditions and no quota. Schedulable, unsized.
     */
    FREE(true, false),

    /**
     * Eating, home, flat, self-care. ADR-040 D19 vetoed this band to the generator; Daniel's later
     * directive lifts the veto — only the agenda blocks placement. Kept as a distinct purpose because
     * it still names what the band is for, and because reinstating the veto is then one flag.
     */
    HOUSEHOLD(true, false),

    /**
     * The wind-down towards sleep. Placeable since Daniel's directive; the bedtime edge of the sleep
     * frontier is what actually closes the day.
     */
    WIND_DOWN(true, false);

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
