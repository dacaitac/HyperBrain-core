package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The walls the {@code AgendaValidator} re-imposes on any proposed agenda before it is persisted.
 * Independent of how the agenda was produced, so the same guard protects both the deterministic
 * generator (#6a) and the future LLM (#6b): the validator is the non-negotiable frontier.
 *
 * @param frontierStart  the concrete lower edge of the sleep frontier (wake); never null
 * @param frontierEnd    the concrete upper edge of the sleep frontier (bedtime); never null, after
 *                       {@code frontierStart}
 * @param occupied       the hard walls already spoken for (existing blocks, AGENDA windows); never null
 * @param highLoadQuota  the F6 high-load quota; never negative
 * @param readOnlyAgendaIds the executables that are read-only AGENDA (ADR-009) and must never be
 *                          scheduled as work; never null
 */
public record ValidationContext(
    OffsetDateTime frontierStart,
    OffsetDateTime frontierEnd,
    List<OccupiedInterval> occupied,
    int highLoadQuota,
    Set<UUID> readOnlyAgendaIds
) {

    public ValidationContext {
        if (frontierStart == null || frontierEnd == null) {
            throw new IllegalArgumentException("frontier edges must not be null");
        }
        if (!frontierEnd.isAfter(frontierStart)) {
            throw new IllegalArgumentException(
                "frontierEnd must be after frontierStart: " + frontierStart + " .. " + frontierEnd);
        }
        if (highLoadQuota < 0) {
            throw new IllegalArgumentException("highLoadQuota must be non-negative: " + highLoadQuota);
        }
        occupied = occupied == null ? List.of() : List.copyOf(occupied);
        readOnlyAgendaIds = readOnlyAgendaIds == null ? Set.of() : Set.copyOf(readOnlyAgendaIds);
    }
}
