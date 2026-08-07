package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One executable the Planner may place in the day, projected from the aggregate state by the read
 * port with its Prioritizer score already computed (the floor <b>reads</b> {@code priority_score};
 * it never recomputes the Prioritizer). Carries exactly what the deterministic floor needs to size
 * and classify a block, keeping the domain services free of persistence.
 *
  * <p>The learned unit cost and the executed-minutes total this used to carry are gone with the cost
 * estimator (ADR-040): with time estimation retired there is nothing to estimate, and a series nobody
 * reads is machinery that runs to produce dead data.
 *
 * @param id                   the {@code core_executable}; never null
 * @param type                 the executable kind; never null
 * @param priorityScore        the Prioritizer score already persisted, in {@code [0, 1]}; the ranking
 *                             key (highest first). Null is treated as the neutral floor when ranking.
 * @param inProgress           true when {@code status = IN_PROGRESS} (candidate for the "paused" list
 *                             when it ends up with no open block)
 * @param energyDrain          {@code core_execution_profile.energy_drain} on the 1–5 scale; null when
 *                             unprofiled (treated as not high-load)
 * @param pendingSubtasks      count of pending user subtasks; used with {@code learnedUnitCost}
 * @param estimatedMinutes     {@code core_execution_profile.estimated_minutes}; used by the
 *                             without-subtasks branch; null when unestimated
 * @param dueInstant           the executable's due timestamp ({@code COALESCE(end_time, start_time)});
 *                             when non-null it scopes WHICH day the executable is schedulable (the
 *                             admission rule in {@code AgendaGenerationService}, ADR-040 D3: today's
 *                             items plus the dateless bag, nothing else). Since ADR-026 D4 it does not
 *                             seed WHERE inside the day the work lands, and since the window model it
 *                             does not seed anything at all — work sits at the start of the window that
 *                             holds it. Null when no due date is set
 * @param cycleId              the {@code core_executable.cycle_id} this executable belongs to; the
 *                             context key the humanized floor batches on (same Cycle/type placed
 *                             adjacently to cut context-switching, H1 rule 4); null when the executable
 *                             hangs off no cycle
 */
public record SchedulableExecutable(
    UUID id,
    ExecutableType type,
    Double priorityScore,
    boolean inProgress,
    Integer energyDrain,
    int pendingSubtasks,
    Integer estimatedMinutes,
    OffsetDateTime dueInstant,
    UUID cycleId
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
        // dueInstant nullable: no validation needed
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
