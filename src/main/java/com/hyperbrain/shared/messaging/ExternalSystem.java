package com.hyperbrain.shared.messaging;

/**
 * Origin or target system of a synchronized change, parsed from the outbox
 * {@code source_system} column. Drives the framework-level loop protection of the outbox
 * drain (RF-17, HU-14 CA-10): an event never propagates back to the system it came from.
 */
public enum ExternalSystem {
    APPLE,
    NOTION,
    SYSTEM,
    HYPERBRAIN_CORE,
    /** Absent or unrecognized {@code source_system}; propagators must not act on it. */
    UNKNOWN;

    /**
     * Parses an outbox {@code source_system} value, mapping {@code null} or unknown labels
     * to {@link #UNKNOWN} instead of failing — a malformed origin must never poison the drain.
     *
     * @param sourceSystem the raw column value; may be {@code null}
     * @return the matching constant, or {@link #UNKNOWN}
     */
    public static ExternalSystem from(String sourceSystem) {
        if (sourceSystem == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(sourceSystem);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }

    /**
     * Whether a change carrying this origin was made by a person on a satellite — the two surfaces the
     * user actually edits, Notion and Apple — as opposed to one the system itself wrote. Several domain
     * rules turn on exactly that distinction (a human move is re-asserted with a visible note; a human
     * containment hands the block over to him), so the predicate lives here rather than being restated
     * per rule.
     *
     * @return true for {@link #APPLE} and {@link #NOTION}
     */
    public boolean isHumanEdit() {
        return this == APPLE || this == NOTION;
    }
}
