package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.HumanizationContext;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single entry point of the <b>humanized deterministic floor</b> (H1, HU-01c): it composes the
 * three humanization stages around the raw {@link AgendaGenerator} so the output is a day a human can
 * live — spaced, meal-protected, un-fragmented, context-batched and deliberately un-packed — rather
 * than a wall of glued 30-minute blocks.
 *
 * <p><b>Pipeline</b> (deterministic, pure function of the state):
 * <ol>
 *   <li><b>Batch (rule 4).</b> {@link ContextBatcher} regroups the ranked executables by context within
 *       comparable-priority bands, so the generator places same-context work adjacently.</li>
 *   <li><b>Generate (rules 1, 2, 5).</b> The {@link AgendaGenerator} places the batched work, reserving
 *       a transition buffer after each block (rule 1) and planning around the meal-anchor walls and any
 *       stable habit anchors already present in {@code state.occupied} / pinned via {@code dueInstant}
 *       (rules 2, 5).</li>
 *   <li><b>Humanize (rules 3, 6).</b> {@link AgendaHumanizer} drops sub-minimum slivers and trims the
 *       day to the occupancy cap.</li>
 * </ol>
 *
 * <p><b>DEGRADED hook (ADR-019).</b> This is the method the propose-then-validate orchestrator invokes
 * as its DEGRADED fallback: when the LLM proposal fails or the {@code AgendaValidator} rejects it, the
 * orchestrator returns <em>this</em> humanized floor instead of the raw generator output — a clearly
 * better day than the flat baseline. The caller is responsible for having assembled the
 * {@link PlannerDayState} (including the meal-anchor walls in {@code occupied}); the same assembly the
 * baseline uses, so both paths yield the identical humanized shape.
 *
 * <p>Design pattern: Composite / pipeline of single-algorithm domain services — each stage is a pure
 * transform, and this class only sequences them; no state, no clock.
 */
public class HumanizedAgendaFloor {

    private final ContextBatcher contextBatcher;
    private final AgendaGenerator agendaGenerator;
    private final AgendaHumanizer agendaHumanizer;
    private final HumanizationSettings settings;

    /**
     * Composes the humanized floor from its stages.
     *
     * @param contextBatcher  the pre-generation batcher; never null
     * @param agendaGenerator the placement engine, already configured with the humanization buffer;
     *                        never null
     * @param agendaHumanizer the post-placement humanizer; never null
     * @param settings        the humanization calibration; never null
     */
    public HumanizedAgendaFloor(ContextBatcher contextBatcher, AgendaGenerator agendaGenerator,
                                AgendaHumanizer agendaHumanizer, HumanizationSettings settings) {
        if (contextBatcher == null || agendaGenerator == null || agendaHumanizer == null
            || settings == null) {
            throw new IllegalArgumentException("collaborators and settings must not be null");
        }
        this.contextBatcher = contextBatcher;
        this.agendaGenerator = agendaGenerator;
        this.agendaHumanizer = agendaHumanizer;
        this.settings = settings;
    }

    /**
     * Runs the full humanized pipeline over the assembled day state.
     *
     * @param state the resolved day state, with meal-anchor walls already folded into
     *              {@code occupied}; never null
     * @return the humanized agenda: spaced, meal-protected, un-fragmented and occupancy-capped
     */
    public Agenda generate(PlannerDayState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }

        var batched = contextBatcher.batch(state.rankedExecutables(), settings.batchBandWidth());
        PlannerDayState batchedState = new PlannerDayState(
            state.windowStart(), state.windowEnd(), batched, state.wigPortfolio(),
            state.occupied(), state.energyProfile(), state.dataComplete());

        Agenda raw = agendaGenerator.generate(batchedState);

        HumanizationContext context = new HumanizationContext(
            state.windowStart(), state.windowEnd(), priorityIndex(state.rankedExecutables()));
        return agendaHumanizer.humanize(raw, context, settings);
    }

    private static Map<UUID, Double> priorityIndex(java.util.List<SchedulableExecutable> ranked) {
        Map<UUID, Double> index = new LinkedHashMap<>();
        for (SchedulableExecutable executable : ranked) {
            index.put(executable.id(), executable.rankingScore());
        }
        return index;
    }
}
