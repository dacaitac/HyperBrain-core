package com.hyperbrain.planner.domain.model;

import java.util.List;

/**
 * The outcome of running an agenda through the {@code AgendaValidator}: the {@code accepted} blocks
 * safe to persist as {@code PLANNED}, and the {@code violations} the validator rejected. A clean run
 * yields the same blocks with an empty violation list; a dirty proposal (from a buggy generator or,
 * later, the LLM) has the offending blocks stripped and reported — never persisted.
 *
 * <p>The WIG block is never a violation: F1 is intocable, so the validator preserves it even when the
 * F6 quota is already spent.
 *
 * @param accepted   the blocks that cleared every wall, chronological; never null
 * @param violations the rejected blocks paired with the wall they hit; never null
 */
public record ValidatedAgenda(List<AgendaBlock> accepted, List<ValidationViolation> violations) {

    public ValidatedAgenda {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** @return true when no block was rejected */
    public boolean isClean() {
        return violations.isEmpty();
    }
}
