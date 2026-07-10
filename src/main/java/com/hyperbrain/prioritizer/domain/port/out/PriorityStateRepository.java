package com.hyperbrain.prioritizer.domain.port.out;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;

import java.util.List;
import java.util.Map;
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
     * Persists the computed Priority Scores into {@code core_executable.priority_score}. Writes only
     * that column; every other attribute is left untouched.
     *
     * @param scores the scores to persist; never null, may be empty
     */
    void saveScores(List<PriorityScore> scores);
}
