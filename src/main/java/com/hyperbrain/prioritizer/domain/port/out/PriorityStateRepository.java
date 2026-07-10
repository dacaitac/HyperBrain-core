package com.hyperbrain.prioritizer.domain.port.out;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Out-port through which the Prioritizer reads the domain state it scores and writes the result back.
 * The Prioritizer reads aggregate state directly (Daniel, 2026-07-09) — it does not consume the
 * UserStateReadModel (#61), which is the LLM tier's contract (#6b), not the deterministic floor's.
 *
 * <p>The implementation lives in {@code prioritizer.infrastructure} (JDBC), keeping the domain free
 * of persistence.
 */
public interface PriorityStateRepository {

    /**
     * Gathers the raw priority factors for the executables that make up the user's day — the pending
     * ({@code TODO}/{@code IN_PROGRESS}) executables joined with their execution profile — with their
     * urgency already derived onto the 0–6 source scale.
     *
     * @param userId the owning user
     * @return the day's executables' un-normalized factors; never null, may be empty
     */
    List<ExecutableFactors> findTodaysFactors(UUID userId);

    /**
     * Gathers the raw priority factors for a single executable, with its urgency derived onto the
     * 0–6 source scale exactly as {@link #findTodaysFactors}. Used by the on-event recompute
     * ({@code rescore}) to score one row without loading the whole day.
     *
     * @param executableId the executable to score
     * @return its un-normalized factors, or empty when the row does not exist (e.g. a CREATE not yet
     *         persisted, or a system-generated / read-only AGENDA row that carries no priority)
     */
    Optional<ExecutableFactors> findFactors(UUID executableId);

    /**
     * Gathers the graded-alignment context for every cycle the user's executables may belong to: for
     * each {@code core_cycle}, its own horizon type and every {@code ACTIVE} ancestor reachable by
     * walking {@code parent_cycle_id} upward, tagged with the minimum hop-distance to it. A single
     * recursive pass computes the whole run (no per-executable lookup); the free-form parent graph
     * (ADR-015) is walked with a cycle guard and a defensive depth bound.
     *
     * @param userId the owning user
     * @return the alignment context per cycle id; never null, empty when the user has no cycles
     */
    Map<UUID, CycleAlignmentContext> findAlignmentContexts(UUID userId);

    /**
     * Gathers the graded-alignment context for a single cycle — the single-source specialization of
     * {@link #findAlignmentContexts} used by the on-event {@code rescore}. Same recursive
     * {@code parent_cycle_id} walk, cycle guard and depth bound, scoped to one starting cycle.
     *
     * @param cycleId the source cycle
     * @return its alignment context, or empty when the cycle does not exist
     */
    Optional<CycleAlignmentContext> findAlignmentContext(UUID cycleId);

    /**
     * Persists the computed scores into {@code core_executable}: {@code priority_score} (normalized
     * {@code [0, 1]}), {@code urgency_score} (raw 0–6) and {@code priority_computed_at} (the recompute
     * clock, stamped {@code now()}). Writes only those columns; every other attribute is untouched.
     *
     * <p>Each row is diffed against its currently persisted {@code priority_score}/{@code urgency_score}
     * with a floating-point epsilon: a row whose score is unchanged is skipped (no write, no clock
     * bump), so downstream propagation fires only for executables whose priority actually moved.
     *
     * @param scores the scores to persist; never null, may be empty
     * @return the ids of the executables whose persisted score changed; never null, may be empty
     */
    Set<UUID> saveScores(List<PriorityScore> scores);
}
