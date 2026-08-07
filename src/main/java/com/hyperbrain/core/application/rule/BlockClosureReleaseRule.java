package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.application.OverdueSweepService;
import com.hyperbrain.core.domain.model.CompletionState;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Closing a block retires the work it still held (Daniel, 2026-08-07): when a {@code TIME_BLOCK}
 * transitions to a closed status, its uncompleted members go through the ADR-040 D4 table — habits and
 * lead measures close as a sanctioned miss and clone if they recur, tasks are rescheduled onto the
 * current day with their containment released first, and purchases go back to the dateless bag.
 *
 * <p><b>The bug this closes.</b> Marking a block done in Notion left its unfinished members stranded:
 * still contained, still carrying the block's window as the SYSTEM-owned hard copy (DR-10), and
 * therefore invisible to every later day, which only admits what is dated for it (ADR-040 D3). Seven
 * rows were sitting in exactly that state in production.
 *
 * <p><b>Why it lives in the ingestion chain.</b> The manual road is the only one that matters here: a
 * human ticks the block in Notion and it arrives as an ordinary merge. Hanging the trigger off the
 * planner would leave that road dead. The elapsed-window settlement calls the same operation directly,
 * so both roads share one implementation — the rule this class is, is the chain's adapter to it.
 *
 * <p><b>Guards.</b> Transition-only, which is also the echo guard: our own write-back is projected to
 * Notion and read straight back as closed→closed, and a transition that did not happen releases
 * nothing. System-generated rows are skipped, and so is a CREATE that is born closed — a block nobody
 * ever put work into has nothing to let go.
 */
@Component
public class BlockClosureReleaseRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(BlockClosureReleaseRule.class);

    private static final String TIME_BLOCK = "TIME_BLOCK";

    private final OverdueSweepService sweepService;
    private final ExecutableStateRepository stateRepo;

    public BlockClosureReleaseRule(OverdueSweepService sweepService,
                                   ExecutableStateRepository stateRepo) {
        this.sweepService = sweepService;
        this.stateRepo = stateRepo;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.systemGenerated() || !TIME_BLOCK.equals(merged.type())
            || !becameClosed(previous, merged)) {
            return merged;
        }
        log.info("Block {} was closed from {}; retiring the work it still held",
            merged.id(), origin);
        sweepService.releaseMembersOf(
            merged.id(), OffsetDateTime.now(), stateRepo.findUserZone(merged.userId()));
        return merged;
    }

    /** A row born closed carries no transition: on CREATE {@code previous} is null and nothing fires. */
    private static boolean becameClosed(ExecutableSnapshot previous, ExecutableSnapshot merged) {
        return previous != null
            && CompletionState.isClosed(merged.status())
            && !CompletionState.isClosed(previous.status());
    }
}
