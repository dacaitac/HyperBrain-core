package com.hyperbrain.planner.domain.model;

/**
 * The three window sizes of ADR-040 and the internal split each one yields.
 *
 * <p><b>The split is information, never a constraint.</b> It is written into the block's description
 * and its single function is that Daniel reads the window and sets his timer. There is no sum of
 * estimates to compare against the effective minutes, because ADR-040 retires time estimation
 * altogether: nothing here caps a window's membership, sizes a block or rejects a placement.
 *
 * <p>{@link #UNSIZED} is the whirlwind's answer: those blocks may last whatever they have to (a hundred
 * and six minutes, if that is what the slot is), which is exactly what makes the template's slots that
 * are not a multiple of any size harmless.
 */
public enum WindowSize {

    /** Thirty minutes — one 25 + 5 stretch. */
    SHORT(30, "25 + 5"),

    /** One hour, for interruptible or reactive work — 50 effective minutes. */
    STANDARD(60, "25+5 ×2  ·  30+5+20+5  ·  50+10"),

    /** Two hours, for deep work — 90 to 100 effective minutes. */
    DEEP(120, "30+5 ×3 + 20  ·  25+5 ×4 + 5  ·  50+10 ×2  ·  90+30"),

    /** No size model applies (the whirlwind and every unsized purpose). */
    UNSIZED(0, null);

    private final int minutes;
    private final String internalSplit;

    WindowSize(int minutes, String internalSplit) {
        this.minutes = minutes;
        this.internalSplit = internalSplit;
    }

    /** @return the nominal window length in minutes; {@code 0} for {@link #UNSIZED} */
    public int minutes() {
        return minutes;
    }

    /** @return the readable internal split for the block description; null for {@link #UNSIZED} */
    public String internalSplit() {
        return internalSplit;
    }

    /**
     * Classifies a slot's duration into the nearest sanctioned size, for a slot whose purpose is sized.
     * A duration that matches none exactly falls to the largest size it fully contains, and to
     * {@link #UNSIZED} when it contains none — the template stays soft, so an odd slot degrades into an
     * unsized window instead of being rejected.
     *
     * @param durationMinutes the slot's duration in minutes; must be positive
     * @return the sanctioned size; never null
     */
    public static WindowSize of(int durationMinutes) {
        if (durationMinutes >= DEEP.minutes) {
            return DEEP;
        }
        if (durationMinutes >= STANDARD.minutes) {
            return STANDARD;
        }
        if (durationMinutes >= SHORT.minutes) {
            return SHORT;
        }
        return UNSIZED;
    }
}
