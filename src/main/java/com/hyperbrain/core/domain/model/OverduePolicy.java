package com.hyperbrain.core.domain.model;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/*
 * Design pattern: table-driven dispatch (a Strategy selector reduced to a lookup table)
 * Reason: ADR-040 D4 is literally a seven-row table of type -> outcome. Encoding it as one
 * immutable map keeps the rule readable next to the ADR and gives the SQL candidate filter and
 * the sweep service a single source of truth for which types are swept at all.
 */

/**
 * ADR-040 D4 — what happens to an executable that expired, by type. The system does not drag the
 * past in bulk: each type closes its day its own way, and the difference between them is what
 * keeps tomorrow's agenda clean without losing anything on the way.
 *
 * <table>
 *   <caption>The dispatch table</caption>
 *   <tr><th>Type</th><th>Outcome</th></tr>
 *   <tr><td>{@code HABIT}</td><td>{@code FAILED}</td></tr>
 *   <tr><td>{@code LEAD_MEASURE}</td><td>{@code FAILED}</td></tr>
 *   <tr><td>{@code TASK}</td><td>re-dated to the next day (never closed, never cloned)</td></tr>
 *   <tr><td>{@code AGENDA}</td><td>{@code FAILED}, cloned when it has a frequency (same as ACTIVITY)</td></tr>
 *   <tr><td>{@code ACTIVITY}</td><td>{@code FAILED}, cloned only when it has a frequency</td></tr>
 *   <tr><td>{@code BUYING}</td><td>date cleared (back to the dateless bag)</td></tr>
 *   <tr><td>{@code TIME_BLOCK}</td><td>{@code DONE}</td></tr>
 * </table>
 *
 * <p>Two types are deliberately absent from {@link #sweptTypes()} and resolve to
 * {@link OverdueAction#NONE}:
 * <ul>
 *   <li>{@code TIME_BLOCK} — a block's window closes intraday, not at day close, so its terminal
 *       is written by the block settlement path on its own (one-minute) cadence. ADR-040 D4 only
 *       changes <em>which</em> terminal that path writes: {@code DONE}, not {@code FAILED} — a
 *       block is not a broken commitment, it is a time container that elapsed.</li>
 *   <li>{@code LEARNING_SESSION} — ADR-040 D4 defines seven behaviours and this type is not one of
 *       them; its lifecycle belongs to the FSRS engine. Leaving it untouched is the honest reading
 *       of the decision, not an omission.</li>
 * </ul>
 */
public final class OverduePolicy {

    private static final Map<String, OverdueAction> ACTION_BY_TYPE = Map.of(
        "HABIT", OverdueAction.CLOSE_AS_FAILED,
        "LEAD_MEASURE", OverdueAction.CLOSE_AS_FAILED,
        "ACTIVITY", OverdueAction.CLOSE_AS_FAILED,
        "AGENDA", OverdueAction.CLOSE_AS_FAILED,
        "TASK", OverdueAction.RESCHEDULE,
        "BUYING", OverdueAction.CLEAR_DATE);

    /** Stable iteration order keeps the generated candidate filter deterministic across runs. */
    private static final Set<String> SWEPT_TYPES =
        Set.copyOf(new LinkedHashSet<>(ACTION_BY_TYPE.keySet()));

    private OverduePolicy() {
    }

    /**
     * Resolves the day-close outcome of one executable type.
     *
     * @param type the {@code core_executable.type} value; may be null
     * @return the action to apply, or {@link OverdueAction#NONE} for the types the sweep leaves alone
     */
    public static OverdueAction actionFor(String type) {
        return type == null ? OverdueAction.NONE : ACTION_BY_TYPE.getOrDefault(type, OverdueAction.NONE);
    }

    /**
     * The types the sweep loads candidates for — every type whose action is not
     * {@link OverdueAction#NONE}. Single-sources the SQL candidate filter off this table so the
     * query can never drift from the dispatch.
     *
     * @return an immutable set of {@code core_executable.type} values
     */
    public static Set<String> sweptTypes() {
        return SWEPT_TYPES;
    }
}
