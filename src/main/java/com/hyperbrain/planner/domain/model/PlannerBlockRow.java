package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One planner block as it is persisted: a {@code TIME_BLOCK} executable (ADR-039 — the block IS the
 * executable, the old separate table is frozen history) with the identity the reconciliation assigned
 * it.
 *
 * <p>{@code name} is only honoured when the row is <b>inserted</b>. A block the plan continues keeps
 * the name it already had, because grouping and naming belong to the LLM and they survive a replan
 * (ADR-040 D8); a replan recomputes where the block sits in time and nothing else.
 *
 * @param blockId        the block executable's id — inherited from the block it continues, or freshly
 *                       minted; never null
 * @param userId         the owning user; never null
 * @param name           the block's display name, written on insert only; never blank
 * @param description    the readable block note: why it is there and, for a sized window, the internal
 *                       split Daniel reads to set his timer; may be null
 * @param start          the block start instant; never null
 * @param end            the block end instant; never null, strictly after {@code start}
 * @param templateSlotId the template band the block realises; null when it comes from no template
 */
public record PlannerBlockRow(
    UUID blockId,
    UUID userId,
    String name,
    String description,
    OffsetDateTime start,
    OffsetDateTime end,
    String templateSlotId
) {

    public PlannerBlockRow {
        if (blockId == null || userId == null) {
            throw new IllegalArgumentException("blockId and userId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a block must carry a display name");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: " + start + " .. " + end);
        }
        name = name.strip();
        templateSlotId = templateSlotId == null || templateSlotId.isBlank()
            ? null : templateSlotId.strip();
    }
}
