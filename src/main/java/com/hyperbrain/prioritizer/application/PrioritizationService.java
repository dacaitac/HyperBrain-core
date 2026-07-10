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
import java.util.UUID;

/**
 * Application service that drives the deterministic floor of the daily agenda (#6a / HU-01a): it
 * reads the user's day and active WIGs from the domain aggregates, computes the normalized Priority
 * Score (v2) for every executable, ranks them, and persists the scores.
 *
 * <p>The read and the write run in one transaction so the persisted scores reflect a single,
 * consistent snapshot of the state.
 */
@Service
public class PrioritizationService {

    private static final Logger log = LoggerFactory.getLogger(PrioritizationService.class);

    private final PriorityStateRepository repository;
    private final PriorityScoreCalculator calculator;

    PrioritizationService(PriorityStateRepository repository, PriorityScoreCalculator calculator) {
        this.repository = repository;
        this.calculator = calculator;
    }

    /**
     * Reprioritizes the user's day: scores and ranks every pending executable and persists the
     * result into {@code core_executable.priority_score}.
     *
     * @param userId the user whose day to reprioritize
     * @return the executables ranked by Priority Score, highest first; never null
     */
    @Transactional
    public List<PriorityScore> reprioritizeToday(UUID userId) {
        List<ExecutableFactors> factors = repository.findTodaysFactors(userId);
        Map<UUID, CycleAlignmentContext> alignmentContexts = repository.findAlignmentContexts(userId);

        List<PriorityScore> ranked = calculator.rank(factors, alignmentContexts);

        repository.saveScores(ranked);
        log.info("Reprioritized {} executables for user {}", ranked.size(), userId);
        return ranked;
    }
}
