package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.BlockClosureReleaseRule;
import com.hyperbrain.core.application.rule.CompletionOutcomeRule;
import com.hyperbrain.core.application.rule.CompletionReactivationRule;
import com.hyperbrain.core.application.rule.ContainmentAuthorityRule;
import com.hyperbrain.core.application.rule.ContainmentCopyRule;
import com.hyperbrain.core.application.rule.ContainmentEligibilityRule;
import com.hyperbrain.core.application.rule.DomainRule;
import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.core.application.rule.RecurrenceCloneRule;
import com.hyperbrain.core.application.rule.ProgressRecalculationRule;
import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/*
 * Design pattern: Chain of Responsibility (ordered, non-short-circuiting).
 * Reason: the DR catalog grows one rule at a time; the explicit constructor order keeps the
 * chain deterministic without relying on annotation-driven bean ordering.
 */

/**
 * Production {@link DomainChangeProcessor} (ADR-012 D2 + ADR-013 D6): applies the active
 * domain-rule catalog (components.md) as an ordered chain, inside the ingestion transaction.
 * Chain order: DR-01 structural invariant first, DR-02 completion reactivation, then the focus
 * cut (DR-05/DR-06), then the re-estimation confirmation, then the progress recalculation
 * (DR-07), and finally the recurrence cloning (DR-04) — each link receives the snapshot
 * the previous one produced.
 *
 * <p>Priority reflection (#66a) is deliberately <b>not</b> a link here. It is recomputed
 * <em>after</em> the merged row is upserted, by each ingestion service (Notion and Apple alike), so
 * the Prioritizer reads the persisted merged state instead of the stale pre-merge one and its
 * SQL-derived urgency sees the new due date (ADR-020, D2). Driving it as a pre-upsert rule scored a
 * stale snapshot and, for Notion, stranded the SYSTEM-authored score behind the loop guard.
 */
@Component
public class CoreDomainChangeProcessor implements DomainChangeProcessor {

    private final List<DomainRule> rules;

    public CoreDomainChangeProcessor(
        EndTimeInvariantRule endTimeInvariantRule,
        CompletionReactivationRule completionReactivationRule,
        ContainmentEligibilityRule containmentEligibilityRule,
        ContainmentAuthorityRule containmentAuthorityRule,
        ContainmentCopyRule containmentCopyRule,
        ProgressRecalculationRule progressRecalculationRule,
        CompletionOutcomeRule completionOutcomeRule,
        RecurrenceCloneRule recurrenceCloneRule,
        BlockClosureReleaseRule blockClosureReleaseRule
    ) {
        // Order notes: the containment-eligibility rule runs first of the containment links so an
        // ineligible type (a calendar event / AGENDA) has its containment cleared before anything acts
        // on it — neither the hand-over below nor the hard copy may work from a containment that is
        // about to be dropped. The authority link comes next: by then the containment of the merged row
        // is settled, and handing the block over to the user is a decision about the block, not about
        // the child's schedule, so the hard-copy rule that follows is unaffected either way. The
        // hard-copy rule itself runs after DR-01, so the child
        // schedule it asserts is already end_time-normalized. The completion-outcome rule (clock +
        // streak) runs before DR-04 cloning so the clone copies the already-updated streak
        // (never-miss-twice on any terminal).
        //
        // The focus links that used to sit between them are gone (ADR-040 D13): marking focus no longer
        // opens a block, cuts the task that was running, leaves an instant snapshot subtask or flags a
        // re-estimation. The whole register produced a series nobody consumed once time estimation was
        // retired — with nothing to estimate there is nothing to learn.
        //
        // The block-closure release runs last of all, and after the hard-copy link on purpose: it lets
        // the members of a block that just closed go, and it must see the row in its final merged
        // shape. It is the manual half of a rule with two callers — the elapsed-window settlement
        // invokes the same operation directly — which is the shape ADR-040 D11 asks for.
        this.rules = List.of(
            endTimeInvariantRule,
            completionReactivationRule,
            containmentEligibilityRule,
            containmentAuthorityRule,
            containmentCopyRule,
            progressRecalculationRule,
            completionOutcomeRule,
            recurrenceCloneRule,
            blockClosureReleaseRule);
    }

    @Override
    public ExecutableSnapshot process(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                      ExternalSystem origin) {
        ExecutableSnapshot state = merged;
        for (DomainRule rule : rules) {
            state = rule.apply(previous, state, origin);
        }
        return state;
    }
}
