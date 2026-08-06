package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.model.CompletionState;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * ADR-039 closure-outcome rule (the FAILED matrix): when an executable transitions to a closed
 * status ({@link CompletionState#isClosed}), the completion clock and the streak pair are
 * updated according to the two completion predicates:
 * <ul>
 *   <li><b>{@code last_completed_at} is stamped on ANY closure</b> — {@code DONE} and
 *       {@code FAILED} alike: the intraday-replan guard must drop a sanctioned miss from
 *       today's plan just like a success.</li>
 *   <li><b>Streaks follow {@code isAchieved} only</b>: {@code DONE} extends
 *       ({@code current_streak + 1}, best updated), {@code FAILED} resets the current streak
 *       and never extends; {@code best_streak} is history and survives.</li>
 * </ul>
 *
 * <p>Everything else in the matrix is enforced where each aggregate lives: {@code FAILED} is
 * outside the WIG weighted progress (planner SQL), outside the settlement imputation and the
 * ledger (DR-08 SQL keeps {@code status = 'DONE'}), and an FSRS lapse by spec (module still
 * empty). DR-04 cloning on any terminal is {@link RecurrenceCloneRule}'s job, ordered after
 * this rule so the clone copies the already-updated streak.
 *
 * <p>Guards: transition-only (re-ingesting an already-closed row is a no-op), never on
 * system-generated snapshots nor on {@code TIME_BLOCK} rows (block settlement owns their clock
 * and blocks have no streaks). Both writes are no-ops on CREATE (the row is persisted after
 * the rules run) — a row born closed carries no observable transition.
 */
@Component
public class CompletionOutcomeRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(CompletionOutcomeRule.class);

    private static final String TIME_BLOCK = "TIME_BLOCK";

    private final ExecutableStateRepository stateRepo;

    public CompletionOutcomeRule(ExecutableStateRepository stateRepo) {
        this.stateRepo = stateRepo;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.systemGenerated() || TIME_BLOCK.equals(merged.type())
            || !becameClosed(previous, merged)) {
            return merged;
        }
        stateRepo.markCompleted(merged.id(), OffsetDateTime.now());
        if (CompletionState.isAchieved(merged.status())) {
            stateRepo.recordAchievedStreak(merged.id());
        } else {
            stateRepo.resetStreak(merged.id());
            log.info("Executable {} closed as FAILED: streak reset, completion clock stamped",
                merged.id());
        }
        return merged;
    }

    private static boolean becameClosed(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return CompletionState.isClosed(merged.status())
            && (previous == null || !CompletionState.isClosed(previous.status()));
    }
}
