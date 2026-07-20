package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.CompletionReactivationRule;
import com.hyperbrain.core.application.rule.DomainRule;
import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
import com.hyperbrain.core.application.rule.HabitRecurrenceRule;
import com.hyperbrain.core.application.rule.ProgressRecalculationRule;
import com.hyperbrain.core.application.rule.ReestimationConfirmationRule;
import com.hyperbrain.core.application.rule.SingleFocusRule;
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
 * (DR-07), and finally the habit recurrence cloning (DR-04) — each link receives the snapshot
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
        SingleFocusRule singleFocusRule,
        ReestimationConfirmationRule reestimationConfirmationRule,
        ProgressRecalculationRule progressRecalculationRule,
        HabitRecurrenceRule habitRecurrenceRule
    ) {
        this.rules = List.of(
            endTimeInvariantRule,
            completionReactivationRule,
            singleFocusRule,
            reestimationConfirmationRule,
            progressRecalculationRule,
            habitRecurrenceRule);
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
