package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

/*
 * Design pattern: Chain of Responsibility (ordered, non-short-circuiting).
 * Reason: the DR catalog grows one rule at a time (DR-01..DR-08 today, Prioritizer next);
 * each rule is a small, independently testable link the CoreDomainChangeProcessor applies
 * in a fixed order, threading the (possibly rewritten) snapshot through the chain.
 */

/**
 * One rule of the domain catalog (components.md — "Reglas de dominio activas"), applied by
 * {@link com.hyperbrain.core.application.CoreDomainChangeProcessor} inside the ingestion
 * transaction. A rule may rewrite the snapshot, perform SYSTEM-owned side writes and append
 * outbox events, but must stay cheap and synchronous (ADR-012 D2).
 */
public interface DomainRule {

    /**
     * Applies this rule to one merged executable state.
     *
     * @param previous the persisted state before this ingestion; null on CREATE
     * @param merged   the state produced by the previous link of the chain
     * @param origin   the external system that produced the inbound change
     * @return the state handed to the next link; never null
     */
    ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                             ExternalSystem origin);
}
