package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.PlannerDayState;

/**
 * The single entry point of the <b>deterministic floor</b>: it batches the ranked candidates by context
 * and then fills the day's windows with them.
 *
 * <p><b>Pipeline</b> (deterministic, a pure function of the state):
 * <ol>
 *   <li><b>Batch.</b> {@link ContextBatcher} regroups the ranked executables by context inside bands of
 *       comparable priority, so same-context work tends to land in the same window. It reorders within
 *       a band and never across bands.</li>
 *   <li><b>Fill.</b> The {@link AgendaGenerator} deals the batched work into the windows already laid
 *       for the day.</li>
 * </ol>
 *
 * <p>The post-placement stage this pipeline used to end with is gone: dropping sub-minimum slivers and
 * trimming the day to an occupancy cap were two of the four capacity discounts ADR-040 D1 retired.
 * Nothing recuts the day after the windows are filled.
 *
 * <p><b>DEGRADED hook (ADR-019, as amended by ADR-040 D6).</b> This is what the propose-then-validate
 * orchestrator falls back to when the intelligent layer is unavailable or its proposal is rejected: the
 * day comes out <b>laid and ordered but neither grouped nor named</b>, and the intelligent layer
 * regroups and renames it when it returns. A day without grouping is a worse day; a day without
 * structure is not a day.
 *
 * <p>Design pattern: pipeline of pure domain services — this class only sequences them; no state, no clock.
 */
public class HumanizedAgendaFloor {

    private final ContextBatcher contextBatcher;
    private final AgendaGenerator agendaGenerator;
    private final HumanizationSettings settings;

    /**
     * Composes the floor from its stages.
     *
     * @param contextBatcher  the pre-fill batcher; never null
     * @param agendaGenerator the window-filling engine; never null
     * @param settings        the surviving calibration; never null
     */
    public HumanizedAgendaFloor(ContextBatcher contextBatcher, AgendaGenerator agendaGenerator,
                                HumanizationSettings settings) {
        if (contextBatcher == null || agendaGenerator == null || settings == null) {
            throw new IllegalArgumentException("collaborators and settings must not be null");
        }
        this.contextBatcher = contextBatcher;
        this.agendaGenerator = agendaGenerator;
        this.settings = settings;
    }

    /**
     * Runs the full pipeline over the assembled day state.
     *
     * @param state the resolved day state, windows already laid and walls already folded in; never null
     * @return the day: one block per filled window, ordered, unnamed
     */
    public Agenda generate(PlannerDayState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        var batched = contextBatcher.batch(state.rankedExecutables(), settings.batchBandWidth());
        PlannerDayState batchedState = new PlannerDayState(
            state.windowStart(), state.windowEnd(), state.windows(), state.existingMembership(),
            batched, state.wigPortfolio(),
            state.occupied(), state.energyProfile(), state.dataComplete());
        return agendaGenerator.generate(batchedState);
    }
}
