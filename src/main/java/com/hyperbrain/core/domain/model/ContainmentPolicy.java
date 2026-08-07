package com.hyperbrain.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/*
 * Design pattern: Policy (pure domain function) with two callers.
 * Reason: ADR-040 D11. The two rules that govern containment used to exist only as links of the
 * ingestion chain, which made them properties of the SYNCHRONISATION CHANNEL rather than of the
 * model — assigning a container from anywhere else copied no date, and the Planner could have
 * written containment with no rule noticing. Both alternatives were rejected: writing straight
 * through the persistence port breaks the module isolation ArchUnit verifies, and re-entering the
 * ingestion chain would require fabricating a status and an origin and would drag foreign rules
 * into the planning transaction. So the rules become pure policies, and they get two callers: the
 * chain link, and the operation core publishes for the Planner. One implementation, never a copy.
 */

/**
 * The two invariants of containment (ADR-039, extracted by ADR-040 D11), as pure functions of their
 * inputs — no clock, no persistence, no framework.
 *
 * <ul>
 *   <li><b>DR-11, eligibility</b> ({@link #containable}): only the reminder-backed types may be
 *       members of a {@code TIME_BLOCK}. The calendar-event types and the read-only agenda already
 *       <em>are</em> blocks of time in themselves — they carry their own window — so they can never
 *       be put inside one.</li>
 *   <li><b>DR-10, the hard copy</b> ({@link #assertedSchedule}): a contained executable carries a
 *       SYSTEM-owned copy of its container's window and cycle. The copy honours DR-01 per child (a
 *       reminder-backed type receives no end instant) and a null container cycle carries no signal —
 *       the child keeps its own, because copying a null would destroy alignment data on every daily
 *       plan.</li>
 * </ul>
 */
public final class ContainmentPolicy {

    /** The block type itself: a container owns its own window and never receives a hard copy. */
    public static final String TIME_BLOCK = "TIME_BLOCK";

    /**
     * Types that already are calendar events (or the read-only agenda), and so can never be contained.
     *
     * <p>{@code TIME_BLOCK} is deliberately absent. A block nested under another block is not
     * containment, it is how Notion renders the reused Parent/Sub-task relation, and it is live in
     * production; clearing that link here would yank blocks out of their Notion parent as a side
     * effect of a refactor nobody asked for. The published containment operation refuses a block as a
     * member through an argument precondition instead — a caller contract, not a second criterion.
     */
    private static final Set<String> NON_CONTAINABLE_TYPES =
        Set.of("ACTIVITY", "LEARNING_SESSION", "AGENDA");

    /** Types whose calendar projection is an event and therefore receive the container's end instant. */
    private static final Set<String> EVENT_TYPES = Set.of("ACTIVITY", "AGENDA", "LEARNING_SESSION");

    private ContainmentPolicy() {
    }

    /**
     * DR-11 — whether a type may be a member of a {@code TIME_BLOCK}.
     *
     * @param type the {@code core_executable.type}; may be null (an unknown type is never containable)
     * @return true only for the reminder-backed types
     */
    public static boolean containable(String type) {
        return type != null && !NON_CONTAINABLE_TYPES.contains(type);
    }

    /**
     * DR-10 — the schedule a contained child must carry, derived from its container.
     *
     * @param container       the container's authoritative schedule; never null
     * @param childType       the child's {@code core_executable.type}; never null
     * @param childCycleId    the child's own cycle, kept when the container carries none; may be null
     * @return the values the child must hold; never null
     */
    public static ContainedSchedule assertedSchedule(ContainerSchedule container, String childType,
                                                     UUID childCycleId) {
        if (container == null) {
            throw new IllegalArgumentException("container must not be null");
        }
        if (childType == null) {
            throw new IllegalArgumentException("childType must not be null");
        }
        OffsetDateTime end = EVENT_TYPES.contains(childType) ? container.endTime() : null;
        UUID cycleId = container.cycleId() != null ? container.cycleId() : childCycleId;
        return new ContainedSchedule(container.startTime(), end, cycleId);
    }
}
