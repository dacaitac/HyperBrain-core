package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One executable the Planner may place in the day, projected from the aggregate state by the read
 * port with its Prioritizer score already computed (the floor <b>reads</b> {@code priority_score};
 * it never recomputes the Prioritizer). Carries exactly what the deterministic floor needs to size
 * and classify a block, keeping the domain services free of persistence.
 *
 * <p><b>Remaining-effort inputs (ADR-013 D4).</b> The generator sizes each block by the remaining
 * effort, choosing the branch by {@code learnedUnitCost}:
 * <ul>
 *   <li><b>with subtasks:</b> {@code pendingSubtasks × cu} — used when {@code learnedUnitCost} is
 *       present (the {@code LearnedUnitCostCalculator} already resolved it, cold-start or learned);</li>
 *   <li><b>without subtasks:</b> {@code max(estimatedMinutes − settledActualMinutes, 0)} — used when
 *       {@code learnedUnitCost} is null (no subtasks to multiply).</li>
 * </ul>
 *
 * @param id                   the {@code core_executable}; never null
 * @param type                 the executable kind; never null
 * @param priorityScore        the Prioritizer score already persisted, in {@code [0, 1]}; the ranking
 *                             key (highest first). Null is treated as the neutral floor when ranking.
 * @param inProgress           true when {@code status = IN_PROGRESS} (candidate for the "paused" list
 *                             when it ends up with no open block)
 * @param energyDrain          {@code core_execution_profile.energy_drain} on the 1–5 scale; null when
 *                             unprofiled (treated as not high-load)
 * @param learnedUnitCost      the per-subtask learned cost (cu); present only when the task has
 *                             subtasks — selects the with-subtasks effort branch
 * @param pendingSubtasks      count of pending user subtasks; used with {@code learnedUnitCost}
 * @param estimatedMinutes     {@code core_execution_profile.estimated_minutes}; used by the
 *                             without-subtasks branch; null when unestimated
 * @param settledActualMinutes Σ {@code actual_duration_minutes} of the task's settled blocks; the
 *                             work already spent, subtracted in the without-subtasks branch
 * @param dueInstant           the executable's due timestamp ({@code COALESCE(end_time, start_time)});
 *                             when non-null it scopes WHICH day the executable is schedulable (the
 *                             day-filter in {@code AgendaGenerationService}). Since ADR-026 D4 it no
 *                             longer seeds WHERE inside the day the block lands — placement is the
 *                             Planner's own authorship. Null when no due date is set
 * @param cycleId              the {@code core_executable.cycle_id} this executable belongs to; the
 *                             context key the humanized floor batches on (same Cycle/type placed
 *                             adjacently to cut context-switching, H1 rule 4); null when the executable
 *                             hangs off no cycle
 * @param rescheduleSeed       the instant on the target day at which to <em>reschedule</em> this
 *                             executable — the same wall-clock time-of-day as its last
 *                             {@code core_time_block} (ADR-026 D5/D3), carried forward when the task's
 *                             existing blocks are all {@code EXPIRED} (vencidos) and no live block
 *                             remains. When present the generator seeds the block's start here (never
 *                             overriding a hard wall or the window); null for a fresh placement, where
 *                             the Planner owns the WHERE entirely (ADR-026 D4). Sourced from the
 *                             Planner's own prior slot — not the authorial reminder time D4 retired —
 *                             so the reschedule keeps a task at the hour the system last chose for it
 */
public record SchedulableExecutable(
    UUID id,
    ExecutableType type,
    Double priorityScore,
    boolean inProgress,
    Integer energyDrain,
    Double learnedUnitCost,
    int pendingSubtasks,
    Integer estimatedMinutes,
    int settledActualMinutes,
    OffsetDateTime dueInstant,
    UUID cycleId,
    OffsetDateTime rescheduleSeed
) {

    public SchedulableExecutable {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (pendingSubtasks < 0) {
            throw new IllegalArgumentException("pendingSubtasks must be non-negative: " + pendingSubtasks);
        }
        if (settledActualMinutes < 0) {
            throw new IllegalArgumentException("settledActualMinutes must be non-negative: " + settledActualMinutes);
        }
        // dueInstant and rescheduleSeed nullable: no validation needed
    }

    /**
     * Convenience constructor for a freshly-placed executable with no reschedule seed (the common
     * case: a task the Planner places by its own authorship, ADR-026 D4). Keeps every existing call
     * site — and the whole fresh-placement path — unchanged by defaulting {@code rescheduleSeed} to
     * null.
     */
    public SchedulableExecutable(
        UUID id, ExecutableType type, Double priorityScore, boolean inProgress, Integer energyDrain,
        Double learnedUnitCost, int pendingSubtasks, Integer estimatedMinutes, int settledActualMinutes,
        OffsetDateTime dueInstant, UUID cycleId) {
        this(id, type, priorityScore, inProgress, energyDrain, learnedUnitCost, pendingSubtasks,
            estimatedMinutes, settledActualMinutes, dueInstant, cycleId, null);
    }

    /** @return the ranking key: the priority score, or the neutral floor {@code 0.0} when null */
    public double rankingScore() {
        return priorityScore == null ? 0.0 : priorityScore;
    }

    /**
     * Tells whether this executable is high-load for the F6 quota.
     *
     * @param drainFloor the {@code energy_drain} at/above which a block is high-load
     * @return true when profiled with {@code energy_drain ≥ drainFloor}
     */
    public boolean isHighLoad(int drainFloor) {
        return energyDrain != null && energyDrain >= drainFloor;
    }

    /**
     * The context this executable belongs to for batching (H1 rule 4): its cycle when it has one, else
     * its type. Two executables sharing a context can be placed adjacently to reduce context-switching.
     *
     * @return the cycle id when present, otherwise the executable type as the fallback context key
     */
    public Object contextKey() {
        return cycleId != null ? cycleId : type;
    }
}
