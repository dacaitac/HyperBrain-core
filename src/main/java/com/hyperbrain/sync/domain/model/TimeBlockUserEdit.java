package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * A user's Notion edit of one live time block, reduced to the USER-writable facets of the
 * ADR-038 authority matrix. A {@code null} facet means "unchanged — do not touch": the inbound
 * sync computes the effective diff and only ships what actually moved, so the planner-side
 * adapter never re-applies an echo.
 *
 * @param blockId        the {@code core_time_block.id} being edited; never null
 * @param newStart       the new window start, or null when the window did not change
 * @param newEnd         the new window end; must be non-null iff {@code newStart} is
 * @param newTheme       the new theme text, or null when the title's theme part did not change
 * @param desiredMembers the desired member executable ids, or null when the membership is
 *                       unchanged or unreadable (the {@code has_more} guard)
 */
public record TimeBlockUserEdit(
    UUID blockId,
    OffsetDateTime newStart,
    OffsetDateTime newEnd,
    String newTheme,
    Set<UUID> desiredMembers
) {

    public TimeBlockUserEdit {
        if (blockId == null) {
            throw new IllegalArgumentException("blockId must not be null");
        }
        if ((newStart == null) != (newEnd == null)) {
            throw new IllegalArgumentException("newStart and newEnd must travel together");
        }
        if (newStart != null && !newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("newEnd must be after newStart");
        }
        if (desiredMembers != null) {
            desiredMembers = Set.copyOf(desiredMembers);
        }
    }

    /** @return true when the edit carries a window change */
    public boolean retimes() {
        return newStart != null;
    }

    /** @return true when the edit carries a theme change */
    public boolean rethemes() {
        return newTheme != null;
    }

    /** @return true when the edit carries a membership change */
    public boolean changesMembership() {
        return desiredMembers != null;
    }
}
