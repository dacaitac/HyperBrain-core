package com.hyperbrain.core.application;

import com.hyperbrain.core.application.rule.DomainRule;
import com.hyperbrain.core.application.rule.EndTimeInvariantRule;
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
 * Chain order: DR-01 structural invariant first, then the focus cut (DR-05/DR-06), then the
 * re-estimation confirmation, then the progress recalculation (DR-07) — each link receives the
 * snapshot the previous one produced. The Prioritizer (HU-01) joins this chain as another rule.
 */
@Component
public class CoreDomainChangeProcessor implements DomainChangeProcessor {

    private final List<DomainRule> rules;

    public CoreDomainChangeProcessor(
        EndTimeInvariantRule endTimeInvariantRule,
        SingleFocusRule singleFocusRule,
        ReestimationConfirmationRule reestimationConfirmationRule,
        ProgressRecalculationRule progressRecalculationRule
    ) {
        this.rules = List.of(
            endTimeInvariantRule,
            singleFocusRule,
            reestimationConfirmationRule,
            progressRecalculationRule);
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
