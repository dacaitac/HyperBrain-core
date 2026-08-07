package com.hyperbrain.core.domain.model;

import java.util.UUID;

/**
 * One executable's place inside a block, as offered to the published containment operation.
 *
 * @param memberId       the executable joining the container; never null
 * @param plannedMinutes the member's quota inside the container; may be null (no quota)
 * @param ord            the member's order inside the container — which also drives the order of the
 *                       sub-items in Notion, so the nesting reads as the sequence of a plan and not as
 *                       the decomposition of one thing (ADR-040 D18); never negative
 */
public record ContainmentRequest(UUID memberId, Integer plannedMinutes, int ord) {

    public ContainmentRequest {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId must not be null");
        }
        if (ord < 0) {
            throw new IllegalArgumentException("ord must not be negative: " + ord);
        }
    }
}
