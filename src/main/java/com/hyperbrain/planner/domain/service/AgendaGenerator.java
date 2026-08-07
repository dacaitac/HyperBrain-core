package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.DayWindow;
import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.SlotPurpose;
import com.hyperbrain.planner.domain.model.WigReservationPlan;
import com.hyperbrain.planner.domain.model.WindowSize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The deterministic core of the agenda floor, rebuilt on the window model (ADR-040): given a resolved
 * {@link PlannerDayState} whose windows are already laid, it decides <b>what goes in each window</b>.
 * A pure function of its input — no clock, no persistence.
 *
 * <p><b>What changed, and why it matters.</b> The engine this replaces walked a cursor from the start
 * of the day and gave every task its own block in the first free gap. In production that produced
 * twenty-one flat thirty-minute blocks between 07:00 and 17:30 — a wall nobody executes. The unit of
 * scheduling is now the window, not the task: windows are laid first, against the template and the
 * hard walls, and tasks become <b>members</b> of them.
 *
 * <p><b>Order of operations.</b>
 * <ol>
 *   <li><b>F1 — the goals first.</b> Each active MCI's lead measure claims a window, goal-purpose
 *       windows first, before the ranking touches the day ({@link WigPortfolioSelector}). A claimed
 *       window holds that lead measure alone: a goal window is <em>for</em> the goal.</li>
 *   <li><b>Deal the rest.</b> The remaining candidates, in rank order, are dealt across the remaining
 *       windows in contiguous chunks, so the highest-priority work lands in the earliest window and no
 *       single window becomes the day's dumping ground.</li>
 *   <li><b>Legibility.</b> Every block carries a readable reason and, when its window is sized, the
 *       internal split Daniel reads to set his timer; every skip is recorded in {@code excluded}; every
 *       in-progress executable left without a window is listed in {@code paused}.</li>
 * </ol>
 *
 * <p><b>Nothing places work inside a window.</b> Members sit at the window's start and the user decides
 * what to do and when within it (Daniel, 2026-08-07). The internal split is information printed on the
 * block, never a computation: there are no estimates to add up and nothing caps a window's membership.
 *
 * <p><b>What this engine no longer does</b> (ADR-040 D1, all four retired together because compounded
 * they made roughly half the day unusable and nobody chose that): no chaos margin, no high-load quota,
 * no five-minute cushion after each block, no occupancy cap and no minimum block duration. Availability
 * is expressed on the time axis alone.
 *
 * <p><b>Rigidity (ADR-040 D9).</b> {@code AGENDA} is untouchable and never enters a window — it is the
 * floor the day is laid against. {@code ACTIVITY} and {@code LEARNING_SESSION} are calendar events in
 * their own right, so they are never members either; they may be moved in hour within their day, which
 * is a decision taken outside this engine, on the executables themselves. Everything else is placeable.
 */
public class AgendaGenerator {

    /** Types that own a calendar window of their own and can therefore never be put inside one. */
    private static final Set<ExecutableType> SELF_SCHEDULING_TYPES =
        Set.of(ExecutableType.ACTIVITY, ExecutableType.LEARNING_SESSION);

    private final PlannerConstraints constraints;
    private final WigPortfolioSelector wigPortfolioSelector;

    /** Creates a generator using the sanctioned default constraints. */
    public AgendaGenerator() {
        this(PlannerConstraints.DEFAULT);
    }

    /**
     * Creates a generator with explicit constraints (the calibration seam).
     *
     * @param constraints the planner constraints; never null
     */
    public AgendaGenerator(PlannerConstraints constraints) {
        if (constraints == null) {
            throw new IllegalArgumentException("constraints must not be null");
        }
        this.constraints = constraints;
        this.wigPortfolioSelector = new WigPortfolioSelector(constraints);
    }

    /**
     * Fills the day's windows from the resolved state.
     *
     * @param state the resolved, concrete-day planning state, windows already laid; never null
     * @return the agenda: one block per filled window, exclusions, paused tasks, criterion, degraded flag
     */
    public Agenda generate(PlannerDayState state) {
        List<ExcludedExecutable> excluded = new ArrayList<>();
        Set<UUID> placed = new LinkedHashSet<>();
        Map<DayWindow, List<UUID>> membership = new LinkedHashMap<>();
        List<DayWindow> free = new ArrayList<>(state.windows());

        // F1 — the goals claim their windows before anything else consumes the day.
        int wigBudget = state.energyProfile().highLoadQuota();
        WigReservationPlan plan = wigPortfolioSelector.select(state.wigPortfolio(), wigBudget);
        Map<DayWindow, UUID> wigWindows = new LinkedHashMap<>();
        for (MciWig wig : plan.ordered()) {
            claimWigWindow(wig, free, wigWindows, placed);
        }
        excluded.addAll(plan.excluded());

        List<SchedulableExecutable> candidates =
            admissible(state.rankedExecutables(), placed, excluded, state);
        // A replan starts from the plan that is already there, not from nothing (ADR-040 D8).
        seedFromExistingPlan(state, free, membership, candidates, placed);
        deal(remaining(candidates, placed), free, membership, placed);

        for (SchedulableExecutable candidate : candidates) {
            if (!placed.contains(candidate.id())) {
                excluded.add(new ExcludedExecutable(candidate.id(), ExclusionReason.NO_ROOM_IN_WINDOW));
            }
        }

        List<AgendaBlock> blocks = new ArrayList<>();
        wigWindows.forEach((window, leadMeasureId) -> blocks.add(wigBlock(window, leadMeasureId, state)));
        membership.forEach((window, members) -> blocks.add(themelessBlock(window, members)));
        blocks.sort(Comparator.comparing(AgendaBlock::start));

        List<UUID> paused = pausedExecutables(state.rankedExecutables(), placed);
        return new Agenda(blocks, excluded, paused, state.energyProfile().criterion(),
            !state.dataComplete());
    }

    /**
     * The candidates a window may actually hold, in rank order. Two types never make it: the read-only
     * agenda, which the planner may not touch at all, and the calendar-event types, which already are
     * blocks of time in themselves and are moved in hour rather than contained (ADR-040 D9). A degraded
     * run (F5) keeps only the top few urgents beyond the goals.
     */
    private List<SchedulableExecutable> admissible(List<SchedulableExecutable> ranked, Set<UUID> placed,
                                                   List<ExcludedExecutable> excluded,
                                                   PlannerDayState state) {
        int budget = state.dataComplete() ? Integer.MAX_VALUE : constraints.degradedUrgentCount();
        List<SchedulableExecutable> candidates = new ArrayList<>();
        for (SchedulableExecutable executable : ranked) {
            if (placed.contains(executable.id())) {
                continue;
            }
            if (executable.type() == ExecutableType.AGENDA) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.READ_ONLY_AGENDA));
                continue;
            }
            if (SELF_SCHEDULING_TYPES.contains(executable.type())) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.NOT_CONTAINABLE));
                continue;
            }
            if (candidates.size() >= budget) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.NO_ROOM_IN_WINDOW));
                continue;
            }
            candidates.add(executable);
        }
        return candidates;
    }

    /**
     * Re-seats in each window the work its block already holds — the heart of the conservative replan
     * (ADR-040 D8). Grouping and intention survive; what is recomputed is the placement in time, which
     * the window resolver has already done. Without this, the two cases that actually happen would both
     * feel like the system fighting you: waking late would reshuffle a day you had already ordered the
     * night before, and an interruption at eleven would rewrite what was left of the morning.
     *
     * <p>A member that is no longer a candidate — completed, closed, or moved to another day — is
     * quietly dropped rather than re-seated: the plan is conserved, not frozen. Re-invoking the
     * intelligent layer to regroup on every replan would be exactly the friction D8 exists to avoid,
     * which is why nothing here consults it.
     */
    private static void seedFromExistingPlan(PlannerDayState state, List<DayWindow> free,
                                             Map<DayWindow, List<UUID>> membership,
                                             List<SchedulableExecutable> candidates, Set<UUID> placed) {
        if (state.existingMembership().isEmpty()) {
            return;
        }
        Set<UUID> admissible = new LinkedHashSet<>();
        candidates.forEach(candidate -> admissible.add(candidate.id()));
        for (DayWindow window : free) {
            List<UUID> held = state.existingMembership().get(window.slotId());
            if (held == null) {
                continue;
            }
            List<UUID> survivors = held.stream()
                .filter(admissible::contains)
                .filter(id -> !placed.contains(id))
                .toList();
            if (!survivors.isEmpty()) {
                membership.put(window, new ArrayList<>(survivors));
                placed.addAll(survivors);
            }
        }
    }

    /** The candidates the seeding did not already re-seat, in rank order. */
    private static List<SchedulableExecutable> remaining(List<SchedulableExecutable> candidates,
                                                        Set<UUID> placed) {
        return candidates.stream().filter(candidate -> !placed.contains(candidate.id())).toList();
    }

    /**
     * Deals the ranked candidates across the free windows in contiguous chunks: the earliest window
     * takes the top slice, the next one the following slice, and so on.
     *
     * <p>The rule is deliberately the simplest one that keeps rank order both <em>within</em> and
     * <em>across</em> windows while refusing to turn one window into the day's dumping ground. It is
     * not thematic grouping and does not pretend to be: grouping by context, and naming the result, is
     * the intelligent layer's work (ADR-040 D5). Without it the day still comes out laid and ordered,
     * which is the sanctioned degraded shape (D6) — a day without grouping is a worse day; a day
     * without structure is not a day.
     */
    private static void deal(List<SchedulableExecutable> candidates, List<DayWindow> free,
                             Map<DayWindow, List<UUID>> membership, Set<UUID> placed) {
        if (candidates.isEmpty() || free.isEmpty()) {
            return;
        }
        int windowCount = free.size();
        int total = candidates.size();
        int index = 0;
        for (int slot = 0; slot < windowCount && index < total; slot++) {
            // Ceil-division of what is left over the windows that remain: the shares differ by at most
            // one and every candidate is dealt, with no arithmetic that can leave a window empty while
            // a later one is overfull.
            int remainingWindows = windowCount - slot;
            int share = (total - index + remainingWindows - 1) / remainingWindows;
            DayWindow window = free.get(slot);
            List<UUID> members = membership.computeIfAbsent(window, key -> new ArrayList<>());
            for (int taken = 0; taken < share && index < total; taken++, index++) {
                SchedulableExecutable candidate = candidates.get(index);
                members.add(candidate.id());
                placed.add(candidate.id());
            }
            if (members.isEmpty()) {
                membership.remove(window);
            }
        }
    }

    /**
     * Claims a window for an MCI's lead measure — a goal-purpose window when one is free, otherwise the
     * earliest window left. The claimed window is removed from the pool, so a goal window holds its lead
     * measure and nothing else: the template calls that band «Meta», and honouring that is the point.
     */
    private static void claimWigWindow(MciWig wig, List<DayWindow> free,
                                       Map<DayWindow, UUID> claimed, Set<UUID> placed) {
        UUID leadMeasureId = wig.leadMeasureId();
        if (leadMeasureId == null || placed.contains(leadMeasureId) || free.isEmpty()) {
            return;
        }
        DayWindow window = free.stream()
            .filter(candidate -> candidate.slot().purpose() == SlotPurpose.GOAL)
            .findFirst()
            .orElse(free.get(0));
        free.remove(window);
        claimed.put(window, leadMeasureId);
        placed.add(leadMeasureId);
    }

    private AgendaBlock wigBlock(DayWindow window, UUID leadMeasureId, PlannerDayState state) {
        boolean highLoad = state.rankedExecutables().stream()
            .filter(executable -> executable.id().equals(leadMeasureId))
            .findFirst()
            .map(executable -> executable.isHighLoad(constraints.highLoadDrainFloor()))
            .orElse(false);
        String reason = "WIG reserved first (F1): the active MCI's lead measure holds this window alone"
            + splitSuffix(window);
        return new AgendaBlock(leadMeasureId, window.start(), window.end(), true, highLoad, reason,
            List.of(), null, window.slotId());
    }

    /**
     * One window's block, unnamed by design: the floor lays and orders, the intelligent layer groups and
     * names (ADR-040 D5/D6). The anchor is the highest-ranked member, which is only a persistence
     * convention — identity is anchored to the template slot, never to the membership.
     */
    private static AgendaBlock themelessBlock(DayWindow window, List<UUID> members) {
        String reason = String.format("Laid on the %s window in rank order, %d item(s)%s",
            window.slot().purpose(), members.size(), splitSuffix(window));
        return new AgendaBlock(members.get(0), window.start(), window.end(), false, false, reason,
            members.subList(1, members.size()), null, window.slotId());
    }

    /**
     * The window's internal split, appended to the block's readable reason. Its <b>only</b> function is
     * that Daniel reads the window and sets his timer: it computes nothing and limits nothing, because
     * without estimates there is no sum to compare it against. Empty for an unsized window — a whirlwind
     * block may last whatever it has to.
     */
    private static String splitSuffix(DayWindow window) {
        WindowSize size = window.windowSize();
        return size.internalSplit() == null ? "" : " · timer: " + size.internalSplit();
    }

    private static List<UUID> pausedExecutables(List<SchedulableExecutable> ranked, Set<UUID> placed) {
        List<UUID> paused = new ArrayList<>();
        for (SchedulableExecutable executable : ranked) {
            if (executable.inProgress() && !placed.contains(executable.id())) {
                paused.add(executable.id());
            }
        }
        return paused;
    }
}
