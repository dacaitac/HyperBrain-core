package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.application.AgendaPropuesta.BlockDecision;
import com.hyperbrain.cognitive.application.AgendaPropuesta.Placement;
import com.hyperbrain.cognitive.application.AgendaPropuestaParser.AgendaPropuestaParseException;
import com.hyperbrain.cognitive.application.ProposalTelemetry.DegradeReason;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallGuardResult;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.port.out.AgendaProposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The cognitive orchestrator (HU-01c H3): the fallible LLM primary of the propose-then-validate loop
 * (ADR-019). It builds the prompt from the day's read model, asks the {@link LlmGateway} for an
 * arrangement, parses it into an {@link AgendaPropuesta}, and re-imposes the bounded hard walls with the
 * {@link ProposalWallGuard}. The LLM owns the arrangement; the guard owns the walls; anything that fails
 * — transport error, timeout, unparseable JSON, or a wall breach — returns {@link Optional#empty()} so
 * the planner degrades to the deterministic humanized floor.
 *
 * <p>Implements the planner's {@code AgendaProposer} port (dependency inversion: the only module edge is
 * {@code cognitive → planner}). It is instantiated by {@link
 * com.hyperbrain.cognitive.infrastructure.CognitiveLlmAutoConfiguration} only when the feature flag is on
 * <b>and</b> an {@code LlmGateway} bean actually exists — so a mis-wired or absent provider degrades to
 * the humanized floor instead of failing the context (H1/H2, zero regression). It is a plain class (no
 * stereotype) precisely so its existence is gated by the gateway's, order-safely, in the autoconfig.
 *
 * <p>Design pattern: Strategy behind a port — the deterministic floor and this LLM strategy both produce
 * an {@code Agenda}, selected by configuration, and the caller is agnostic to which ran.
 */
public class AgendaProposalService implements AgendaProposer {

    private static final Logger log = LoggerFactory.getLogger(AgendaProposalService.class);

    private final LlmGateway gateway;
    private final AgendaProposalPromptBuilder promptBuilder;
    private final AgendaPropuestaParser parser;
    private final ProposalWallGuard wallGuard;
    private final ProposalTelemetry telemetry;
    private final double maxDropFraction;

    public AgendaProposalService(LlmGateway gateway, AgendaProposalPromptBuilder promptBuilder,
                                 AgendaPropuestaParser parser, ProposalWallGuard wallGuard,
                                 ProposalTelemetry telemetry, double maxDropFraction) {
        this.gateway = gateway;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.wallGuard = wallGuard;
        this.telemetry = telemetry;
        this.maxDropFraction = maxDropFraction;
    }

    @Override
    public Optional<Agenda> propose(AgendaProposalContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (context.candidateBlocks().isEmpty()) {
            // Nothing to arrange: let the caller keep the (empty) floor rather than prompt the model.
            return Optional.empty();
        }

        String raw;
        try {
            LlmPrompt prompt = promptBuilder.build(context);
            raw = gateway.complete(prompt);
        } catch (RuntimeException ex) {
            log.warn("LLM proposal degraded (gateway): {}", ex.getMessage());
            telemetry.degraded(context, DegradeReason.GATEWAY_FAILURE, ex.getMessage());
            return Optional.empty();
        }

        AgendaPropuesta propuesta;
        try {
            propuesta = parser.parse(raw);
        } catch (AgendaPropuestaParseException ex) {
            log.warn("LLM proposal degraded (invalid JSON): {}", ex.getMessage());
            telemetry.degraded(context, DegradeReason.INVALID_JSON, ex.getMessage());
            return Optional.empty();
        }

        WallGuardResult result = wallGuard.check(propuesta, context);
        if (!result.clean()) {
            log.warn("LLM proposal degraded (wall breach): {} breach(es)", result.breaches().size());
            telemetry.intervened(context, result);
            return Optional.empty();
        }

        // Catastrophic-over-drop backstop: the floor already sized and capped the day, so dropping most
        // of it is a broken proposal, not a coherent plan (it would leave the day near-empty and dump
        // everything on tomorrow). A high threshold (not a tight one) — legitimate spreading is the
        // LLM's authority and must not be clamped; this only catches the "gutted day" pathology.
        if (dropsTooMuch(propuesta, context)) {
            log.warn("LLM proposal degraded (excessive drop): dropped > {} of non-WIG blocks",
                maxDropFraction);
            telemetry.degraded(context, DegradeReason.EXCESSIVE_DROP, "fraction>" + maxDropFraction);
            return Optional.empty();
        }

        Agenda arranged = applyArrangement(propuesta, context);
        telemetry.accepted(context);
        return Optional.of(arranged);
    }

    /**
     * Whether the proposal drops more than {@code maxDropFraction} of the day's non-WIG candidate
     * blocks — the catastrophic-over-drop signal. WIG blocks are never counted (they can never be
     * dropped). A day with no non-WIG candidates can never trip the guard.
     */
    private boolean dropsTooMuch(AgendaPropuesta propuesta, AgendaProposalContext context) {
        long nonWigCandidates = context.candidateBlocks().stream()
            .filter(block -> !block.wig())
            .count();
        if (nonWigCandidates == 0) {
            return false;
        }
        long droppedNonWig = propuesta.decisions().stream()
            .filter(decision -> decision.placement() == Placement.DROP)
            .map(decision -> context.candidate(decision.blockId()))
            .filter(candidate -> candidate != null && !candidate.wig())
            .count();
        return (double) droppedNonWig / nonWigCandidates > maxDropFraction;
    }

    /**
     * Materializes the accepted arrangement into an {@link Agenda}: KEEP and MOVE decisions become
     * blocks (MOVE at the model's new window), DROP decisions are omitted, and the coach note is routed
     * to the block's reason → notes only (ADR-012), falling back to the floor's reason when blank so
     * legibility is preserved. The caller re-attaches the floor's exclusions/paused account and criterion.
     */
    private static Agenda applyArrangement(AgendaPropuesta propuesta, AgendaProposalContext context) {
        List<AgendaBlock> blocks = new ArrayList<>();
        for (BlockDecision decision : propuesta.decisions()) {
            if (decision.placement() == Placement.DROP) {
                continue;
            }
            AgendaBlock candidate = context.candidate(decision.blockId());
            boolean move = decision.placement() == Placement.MOVE;
            blocks.add(new AgendaBlock(
                candidate.executableId(),
                move ? decision.start() : candidate.start(),
                move ? decision.end() : candidate.end(),
                candidate.wig(),
                candidate.highLoad(),
                reason(decision.coachNote(), candidate.reason())));
        }
        blocks.sort(java.util.Comparator.comparing(AgendaBlock::start));
        return new Agenda(blocks, List.of(), List.of(), context.energyCriterion(), false);
    }

    private static String reason(String coachNote, String fallback) {
        return coachNote != null && !coachNote.isBlank() ? coachNote.strip() : fallback;
    }
}
