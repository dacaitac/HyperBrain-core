package com.hyperbrain.core.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A {@code TIME_BLOCK} executable seen as what it is — a window with an identity — for the callers that
 * need to place something inside one without caring about the rest of the row.
 *
 * @param blockId        the block executable's id; never null
 * @param userId         the owning user; never null
 * @param name           the block's display name; never blank
 * @param description    the readable block note; may be null
 * @param start          the window start; never null
 * @param end            the window end; may be null for a block that never got one
 * @param templateSlotId the template band it realises; null when it comes from no template
 */
public record BlockWindow(UUID blockId, UUID userId, String name, String description,
                          OffsetDateTime start, OffsetDateTime end, String templateSlotId) {

    /** Returns this window shifted whole by a number of days, under a fresh identity. */
    public BlockWindow shiftedBy(UUID newBlockId, long days) {
        return new BlockWindow(newBlockId, userId, name, description,
            start == null ? null : start.plusDays(days),
            end == null ? null : end.plusDays(days),
            templateSlotId);
    }
}
