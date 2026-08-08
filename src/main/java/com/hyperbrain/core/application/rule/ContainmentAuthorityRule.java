package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * A membership the user assigns by hand is his for the rest of that day: when a containment change
 * arrives from a satellite with a human origin — he dragged the task into a block in Notion, or into
 * one of his own blocks in Apple — the block that receives it stops being the planner's to rebuild.
 *
 * <p><b>The defect this closes.</b> Daniel leaves the day pre-organised the night before, putting his
 * work inside his own blocks. A block he creates is not the problem — the candidate guard already
 * leaves the members of a block the planner may not re-place alone. A block the <em>planner</em>
 * created is: it is regenerable by definition, so its membership is rebuilt from scratch on the next
 * run and the task he placed in it is dealt somewhere else. The hand-over is what makes his decision
 * outlive the replan, and it is the same molde a {@code USER} block already follows.
 *
 * <p><b>Why it expires on its own, and why that matters.</b> Nothing here is dated and nothing has to
 * be swept: a block belongs to one day, so the hand-over dies with it and tomorrow's plan is composed
 * freely from fresh blocks. A permanent anchor would accumulate — in two weeks the system would be
 * frozen and unable to help (Daniel, 2026-08-08).
 *
 * <p><b>Guards.</b> Change-only, which is also the echo guard: the planner's own containment is
 * mirrored to Notion and read straight back, and a containment that did not change hands over
 * nothing. A {@code SYSTEM} origin is the planner writing its own plan. A cleared containment hands
 * nothing over — there is no window left to anchor to, and the released task simply returns to the
 * day's candidates.
 */
@Component
public class ContainmentAuthorityRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(ContainmentAuthorityRule.class);

    private final ExecutableStateRepository stateRepo;

    public ContainmentAuthorityRule(ExecutableStateRepository stateRepo) {
        this.stateRepo = stateRepo;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        UUID container = merged.containerBlockId();
        if (merged.systemGenerated() || !origin.isHumanEdit() || container == null
            || !containmentChanged(previous, container)) {
            return merged;
        }
        if (stateRepo.claimBlockForUser(container)) {
            log.info("Executable {} was put into block {} from {}; the block is the user's for the "
                + "rest of its day and the plan no longer rebuilds it", merged.id(), container, origin);
        }
        return merged;
    }

    /** A row arriving already contained on CREATE ({@code previous} null) is a hand placement too. */
    private static boolean containmentChanged(ExecutableSnapshot previous, UUID container) {
        return previous == null || !Objects.equals(previous.containerBlockId(), container);
    }
}
