package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;

import java.util.Optional;

/**
 * The dependency-inversion port (ADR-005; ADR-019 propose-then-validate) through which the planner
 * hands a resolved day to the cognitive LLM tier and gets back a <b>proposed arrangement</b> — without
 * a compile-time dependency on {@code cognitive}. The implementation lives in
 * {@code cognitive.application} (the orchestrator), so the only module edge is
 * {@code cognitive → planner}; a {@code planner → cognitive} edge is forbidden by ArchUnit, keeping the
 * two modules acyclic.
 *
 * <p><b>Graceful degradation (ADR-019).</b> The LLM is the fallible primary: any failure — transport
 * error, timeout, unparseable JSON, or a proposal that breaches a hard wall (sleep frontier, read-only
 * AGENDA, WIG protection, structural identity) — is reported as {@link Optional#empty()}, and the
 * caller falls back to the deterministic humanized floor (DEGRADED). The proposer never throws for a
 * degraded proposal and never returns a partially-corrected agenda: the bounded wall guard accepts the
 * LLM's arrangement whole or degrades whole.
 *
 * <p>Wired only when {@code app.cognitive.llm-propose.enabled} is on; with the flag off no
 * implementation bean exists and the planner materializes the humanized floor exactly as before (H1/H2,
 * zero regression).
 */
public interface AgendaProposer {

    /**
     * Proposes an arrangement of the day's candidate blocks, or degrades to empty.
     *
     * @param context the LLM-facing read model of the day (candidates, walls, WIG ids, titles); never null
     * @return the proposed agenda when the LLM cleared every hard wall; empty to signal DEGRADED
     */
    Optional<Agenda> propose(AgendaProposalContext context);
}
