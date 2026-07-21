package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.ExecutableType;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;
import com.hyperbrain.planner.domain.model.WigReservationPlan;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The deterministic core of the agenda floor (#6a): given a resolved {@link PlannerDayState}, it
 * places {@code PLANNED} blocks in the day the same way for both modes — full day and
 * replan-from-now — since the only difference (the lower window bound) is already baked into
 * {@code windowStart}. The generator is a pure function of its input: no clock, no persistence.
 *
 * <p><b>Order of operations.</b>
 * <ol>
 *   <li><b>F1 — WIG portfolio first.</b> Reserve one hard {@code wigBlockMinutes} LEAD_MEASURE block
 *       for <em>each</em> active MCI that requires one ({@link WigPortfolioSelector}), before the
 *       ranking touches the day. The portfolio is stable across the cycle — required pace only orders
 *       the placement. WIG blocks are intocable: energy (F6) and the torbellino never trim them; they
 *       consume the high-load quota first but are never expelled.</li>
 *   <li><b>F3 — chaos margin.</b> Hold back {@code chaosMarginFraction} of the (post-WIG) window as
 *       slack; the ranking fills only the remaining usable minutes.</li>
 *   <li><b>Rank fill.</b> Walk the ranked executables highest-first, sizing each by remaining effort
 *       and placing it in the earliest gap that clears every wall. F6 caps high-load blocks.</li>
 *   <li><b>Legibility.</b> Every block carries a readable reason; every skip is recorded in
 *       {@code excluded} (including a WIG left off for want of a lead measure or a degraded budget);
 *       every IN_PROGRESS executable left without an open block is listed in {@code paused}.</li>
 * </ol>
 *
 * <p><b>F5 — degraded mode.</b> When {@code dataComplete} is false, the floor still guarantees the WIG
 * portfolio plus a few top urgents ({@code degradedUrgentCount}); the WIGs always enter.
 *
 * <p>Design pattern: single-algorithm domain service (Strategy deliberately avoided, YAGNI) — the
 * placement policy is one fixed domain invariant. The {@code AgendaValidator} independently re-imposes
 * every wall this generator honours, so a future generator (the #6b LLM) reuses the same guard.
 */
public class AgendaGenerator {

    private final PlannerConstraints constraints;
    private final HumanizationSettings humanization;
    private final WigPortfolioSelector wigPortfolioSelector;

    /** Creates a generator using the sanctioned default constraints and no humanization (raw floor). */
    public AgendaGenerator() {
        this(PlannerConstraints.DEFAULT);
    }

    /**
     * Creates a generator with explicit constraints and no humanization (raw floor, calibration seam).
     *
     * @param constraints the planner constraints; never null
     */
    public AgendaGenerator(PlannerConstraints constraints) {
        this(constraints, HumanizationSettings.NO_OP);
    }

    /**
     * Creates a generator with explicit constraints and humanization settings (H1, HU-01c). The only
     * placement effect of the humanization here is the transition buffer reserved after each execution
     * block; meal anchors and occupancy are threaded through the surrounding walls and the
     * post-processor, keeping this generator a single, focused placement engine.
     *
     * @param constraints  the planner constraints; never null
     * @param humanization the humanization settings; never null (use {@link HumanizationSettings#NO_OP}
     *                     for the raw floor)
     */
    public AgendaGenerator(PlannerConstraints constraints, HumanizationSettings humanization) {
        if (constraints == null) {
            throw new IllegalArgumentException("constraints must not be null");
        }
        if (humanization == null) {
            throw new IllegalArgumentException("humanization must not be null");
        }
        this.constraints = constraints;
        this.humanization = humanization;
        this.wigPortfolioSelector = new WigPortfolioSelector(constraints);
    }

    /**
     * Materializes the day's agenda from the resolved state.
     *
     * @param state the resolved, concrete-day planning state; never null
     * @return the agenda: placed blocks, exclusions, paused tasks, energy criterion, degraded flag
     */
    public Agenda generate(PlannerDayState state) {
        List<AgendaBlock> blocks = new ArrayList<>();
        List<ExcludedExecutable> excluded = new ArrayList<>();
        List<OccupiedInterval> walls = new ArrayList<>(state.occupied());
        Set<UUID> placed = new LinkedHashSet<>();

        // F1 — reserve the WIG portfolio first, before anything else consumes the window. WIG blocks
        // consume the high-load quota first (they are cognitively demanding) but are never trimmed.
        int wigBudget = state.energyProfile().highLoadQuota();
        WigReservationPlan plan = wigPortfolioSelector.select(state.wigPortfolio(), wigBudget);
        for (MciWig wig : plan.ordered()) {
            reserveWig(wig, state, blocks, walls, placed);
        }
        excluded.addAll(plan.excluded());

        // F3 — the ranking may only fill the window minus the chaos margin (the WIGs bypassed it).
        OffsetDateTime rankingLimit = applyChaosMargin(state);

        int highLoadUsed = (int) blocks.stream().filter(AgendaBlock::highLoad).count();
        int urgentBudget = state.dataComplete() ? Integer.MAX_VALUE : constraints.degradedUrgentCount();
        int urgentPlaced = 0;

        for (SchedulableExecutable executable : state.rankedExecutables()) {
            if (placed.contains(executable.id())) {
                continue;
            }
            if (executable.type() == ExecutableType.AGENDA) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.READ_ONLY_AGENDA));
                continue;
            }
            if (urgentPlaced >= urgentBudget) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.NO_ROOM_IN_WINDOW));
                continue;
            }

            int minutes = RemainingEffortCalculator.remainingMinutes(executable);
            if (minutes <= 0) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.NO_REMAINING_EFFORT));
                continue;
            }

            boolean highLoad = executable.isHighLoad(constraints.highLoadDrainFloor());
            if (highLoad && highLoadUsed >= state.energyProfile().highLoadQuota()) {
                excluded.add(new ExcludedExecutable(
                    executable.id(), ExclusionReason.HIGH_LOAD_QUOTA_EXCEEDED));
                continue;
            }

            // Pinned-start placement: the reminder time is when to START. When the executable has a
            // due instant that falls within the planning window, anchor the block's start to that
            // instant and let it run for its remaining effort (reminder-driven scheduling).
            OffsetDateTime dueInstant = executable.dueInstant();
            if (dueInstant != null) {
                OffsetDateTime pinnedStart = dueInstant;
                OffsetDateTime pinnedEnd = pinnedStart.plusMinutes(minutes);
                if (!pinnedStart.isBefore(state.windowStart()) && !pinnedEnd.isAfter(state.windowEnd())) {
                    blocks.add(new AgendaBlock(executable.id(), pinnedStart, pinnedEnd, false, highLoad,
                        rankReasonPinned(executable, minutes)));
                    walls.add(new OccupiedInterval(executable.id(), pinnedStart, pinnedEnd, false));
                    reserveTransitionBuffer(walls, pinnedEnd);
                    placed.add(executable.id());
                    if (highLoad) highLoadUsed++;
                    urgentPlaced++;
                    continue;
                }
                // Due instant outside window, or the block would run past bedtime (e.g. midnight) —
                // fall through to cursor-based placement.
            }

            Optional<OffsetDateTime> slot =
                earliestSlot(state.windowStart(), rankingLimit, minutes, walls);
            if (slot.isEmpty()) {
                excluded.add(new ExcludedExecutable(executable.id(), ExclusionReason.NO_ROOM_IN_WINDOW));
                continue;
            }

            OffsetDateTime start = slot.get();
            OffsetDateTime end = start.plusMinutes(minutes);
            blocks.add(new AgendaBlock(executable.id(), start, end, false, highLoad,
                rankReason(executable, minutes)));
            walls.add(new OccupiedInterval(executable.id(), start, end, false));
            reserveTransitionBuffer(walls, end);
            placed.add(executable.id());
            if (highLoad) {
                highLoadUsed++;
            }
            urgentPlaced++;
        }

        List<UUID> paused = pausedExecutables(state.rankedExecutables(), placed);
        blocks.sort(Comparator.comparing(AgendaBlock::start));
        return new Agenda(blocks, excluded, paused, state.energyProfile().criterion(),
            !state.dataComplete());
    }

    /**
     * Reserves one hard WIG block for an MCI in the earliest slot that clears the walls. The WIG
     * bypasses the chaos margin and the F6 quota entirely: it may use the full window and is never
     * trimmed. A no-lead-measure MCI never reaches here (the selector excludes it with an alert).
     */
    private void reserveWig(MciWig wig, PlannerDayState state,
                            List<AgendaBlock> blocks, List<OccupiedInterval> walls, Set<UUID> placed) {
        UUID leadMeasureId = wig.leadMeasureId();
        if (placed.contains(leadMeasureId)) {
            return;
        }
        int minutes = constraints.wigBlockMinutes();
        Optional<OffsetDateTime> slot =
            earliestSlot(state.windowStart(), state.windowEnd(), minutes, walls);
        if (slot.isEmpty()) {
            return;
        }
        OffsetDateTime start = slot.get();
        OffsetDateTime end = start.plusMinutes(minutes);
        boolean highLoad = highLoadForWig(state, leadMeasureId);
        blocks.add(new AgendaBlock(leadMeasureId, start, end, true, highLoad,
            "WIG reserved first (F1): active MCI lead measure, ordered by required pace, never trimmed"));
        walls.add(new OccupiedInterval(leadMeasureId, start, end, false));
        reserveTransitionBuffer(walls, end);
        placed.add(leadMeasureId);
    }

    /**
     * Reserves a short transition buffer immediately after a placed block (H1 rule 1): a spacer wall so
     * the next block does not start flush against this one, preventing a wall of glued back-to-back
     * blocks. The buffer is a wall only — never emitted as an {@link AgendaBlock}, so it is never
     * written back to Apple. A no-op when the humanized buffer is zero (raw floor).
     *
     * @param walls  the accumulating wall list; mutated in place
     * @param blockEnd the just-placed block's end instant, where the buffer begins
     */
    private void reserveTransitionBuffer(List<OccupiedInterval> walls, OffsetDateTime blockEnd) {
        int buffer = humanization.transitionBufferMinutes();
        if (buffer <= 0) {
            return;
        }
        walls.add(new OccupiedInterval(null, blockEnd, blockEnd.plusMinutes(buffer), false));
    }

    private boolean highLoadForWig(PlannerDayState state, UUID leadMeasureId) {
        return state.rankedExecutables().stream()
            .filter(e -> e.id().equals(leadMeasureId))
            .findFirst()
            .map(e -> e.isHighLoad(constraints.highLoadDrainFloor()))
            .orElse(false);
    }

    /**
     * F3: the instant past which the ranking may not place, holding back the chaos-margin fraction of
     * the window as slack. The WIGs (already placed) are exempt.
     */
    private static OffsetDateTime applyChaosMargin(PlannerDayState state) {
        long windowMinutes = Duration.between(state.windowStart(), state.windowEnd()).toMinutes();
        long usable = Math.round(windowMinutes * (1.0 - state.energyProfile().chaosMarginFraction()));
        return state.windowStart().plusMinutes(usable);
    }

    /**
     * The earliest window instant at which a {@code minutes}-long block fits without overlapping any
     * wall, scanning gaps left-to-right. Returns empty when no gap up to {@code limit} is long enough.
     */
    private static Optional<OffsetDateTime> earliestSlot(
        OffsetDateTime windowStart, OffsetDateTime limit, int minutes, List<OccupiedInterval> walls) {
        OffsetDateTime cursor = windowStart;
        List<OccupiedInterval> sorted = walls.stream()
            .sorted(Comparator.comparing(OccupiedInterval::start))
            .toList();

        while (!cursor.plusMinutes(minutes).isAfter(limit)) {
            OffsetDateTime candidateStart = cursor;
            OffsetDateTime candidateEnd = cursor.plusMinutes(minutes);
            Optional<OccupiedInterval> clash = sorted.stream()
                .filter(wall -> wall.overlaps(candidateStart, candidateEnd))
                .findFirst();
            if (clash.isEmpty()) {
                return Optional.of(cursor);
            }
            cursor = clash.get().end();
        }
        return Optional.empty();
    }

    private static List<UUID> pausedExecutables(
        List<SchedulableExecutable> ranked, Set<UUID> placed) {
        List<UUID> paused = new ArrayList<>();
        for (SchedulableExecutable executable : ranked) {
            if (executable.inProgress() && !placed.contains(executable.id())) {
                paused.add(executable.id());
            }
        }
        return paused;
    }

    private String rankReason(SchedulableExecutable executable, int minutes) {
        String load = executable.isHighLoad(constraints.highLoadDrainFloor()) ? "high-load" : "standard";
        return String.format(
            "Ranked by priority %.3f, %d min remaining effort, %s",
            executable.rankingScore(), minutes, load);
    }

    private String rankReasonPinned(SchedulableExecutable executable, int minutes) {
        String load = executable.isHighLoad(constraints.highLoadDrainFloor()) ? "high-load" : "standard";
        return String.format(
            "Pinned to start at reminder time %s, %d min remaining effort, priority %.3f, %s",
            executable.dueInstant(), minutes, executable.rankingScore(), load);
    }
}
