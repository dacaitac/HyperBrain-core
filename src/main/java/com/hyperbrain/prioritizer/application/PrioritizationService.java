package com.hyperbrain.prioritizer.application;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.prioritizer.domain.port.out.PriorityStateRepository;
import com.hyperbrain.prioritizer.domain.service.PriorityScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Application service that drives the deterministic floor of the daily agenda (#6a / HU-01a) and the
 * on-event priority reflection to the satellites (#66a): it reads the domain aggregates, computes the
 * normalized Priority Score (v2), and persists the scores.
 *
 * <p>The read and the write of each operation run in one transaction so the persisted scores reflect
 * a single, consistent snapshot of the state.
 */
@Service
public class PrioritizationService implements PrioritizerService {

    private static final Logger log = LoggerFactory.getLogger(PrioritizationService.class);

    private final PriorityStateRepository repository;
    private final PriorityScoreCalculator calculator;

    PrioritizationService(PriorityStateRepository repository, PriorityScoreCalculator calculator) {
        this.repository = repository;
        this.calculator = calculator;
    }

    @Override
    @Transactional
    public RescoreResult rescore(UUID executableId) {
        Optional<ExecutableFactors> factors = repository.findFactors(executableId);
        if (factors.isEmpty()) {
            return RescoreResult.noSignal();
        }
        double alignment = resolveAlignment(factors.get().cycleId());
        PriorityScore score = calculator.score(factors.get(), alignment);
        boolean moved = repository.saveScores(List.of(score)).contains(executableId);
        if (log.isDebugEnabled()) {
            log.debug("Rescored executable {} -> priority {}, urgency {} (moved: {})",
                executableId, score.score(), score.urgency(), moved);
        }
        return RescoreResult.scored(score, moved);
    }

    @Override
    @Transactional
    public Set<UUID> reprioritizeToday(UUID userId) {
        List<ExecutableFactors> factors = repository.findTodaysFactors(userId);
        Map<UUID, CycleAlignmentContext> alignmentContexts = repository.findAlignmentContexts(userId);

        List<PriorityScore> ranked = calculator.rank(factors, alignmentContexts);

        Set<UUID> changed = repository.saveScores(ranked);
        log.info("Reprioritized {} executables for user {} ({} changed)",
            ranked.size(), userId, changed.size());
        return changed;
    }

    /**
     * Resolves the graded alignment factor {@code [0, 1]} for one executable's cycle. Reuses the same
     * domain policy the batch ranking applies (via the calculator), so the on-event score is identical
     * to the batch one for the same state; an unassigned or missing cycle contributes no alignment.
     */
    private double resolveAlignment(UUID cycleId) {
        if (cycleId == null) {
            return 0.0;
        }
        return repository.findAlignmentContext(cycleId)
            .map(context -> calculator.resolveAlignment(cycleId, Map.of(cycleId, context)))
            .orElse(0.0);
    }
}
