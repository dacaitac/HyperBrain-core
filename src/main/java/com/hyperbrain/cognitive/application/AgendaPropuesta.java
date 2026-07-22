package com.hyperbrain.cognitive.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The LLM's proposed disposition of a day (HU-01c H3) — an <b>in-process application contract</b>, not
 * a domain event and not an inter-service contract (it never crosses a queue; documented in
 * {@code components.md}, not {@code 04-contracts/}). It is the parsed, typed form of the model's JSON:
 * a final-state decision per run block id.
 *
 * <p><b>Closed-set {@code blockId} (anti-hallucination).</b> Every {@link BlockDecision#blockId()} must
 * be one of the run's candidate block ids (the "enum" is the closed set handed to the model), and the
 * decisions must cover that set exactly — no invented id, no silently dropped block. Both are re-imposed
 * structurally: unparseable JSON fails at {@code AgendaPropuestaParser}, and set membership/coverage at
 * {@code ProposalWallGuard} (STRUCTURAL_IDENTITY). The validator trusts none of the model's conformance.
 *
 * @param decisions one final-state decision per run block id; never null
 */
public record AgendaPropuesta(List<BlockDecision> decisions) {

    public AgendaPropuesta {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /**
     * The final state the model assigns to one run block.
     *
     * @param blockId   the run block id this decision addresses (must be in the run's closed set); never null
     * @param placement KEEP (unchanged), MOVE (retimed to {@code start}/{@code end}), or DROP; never null
     * @param start     the new start when {@code placement == MOVE}; null otherwise
     * @param end       the new end when {@code placement == MOVE}; null otherwise
     * @param coachNote the model's short justification — routed to the block's notes only (ADR-012),
     *                  never the canonical title; may be null or blank
     */
    public record BlockDecision(
        UUID blockId,
        Placement placement,
        OffsetDateTime start,
        OffsetDateTime end,
        String coachNote
    ) {

        public BlockDecision {
            if (blockId == null) {
                throw new IllegalArgumentException("blockId must not be null");
            }
            if (placement == null) {
                throw new IllegalArgumentException("placement must not be null");
            }
            if (placement == Placement.MOVE && (start == null || end == null)) {
                throw new IllegalArgumentException("MOVE requires both start and end: " + blockId);
            }
            if (placement == Placement.MOVE && !end.isAfter(start)) {
                throw new IllegalArgumentException("MOVE end must be after start: " + blockId);
            }
        }
    }

    /** The final placement the LLM may assign to a run block. */
    public enum Placement {
        /** Keep the block at its floor-proposed time. */
        KEEP,
        /** Retime the block to a new {@code start}/{@code end} (the LLM's arrangement authority). */
        MOVE,
        /** Drop the block from the day (never permitted for a WIG block). */
        DROP
    }
}
