package com.hyperbrain.planner.domain.model;

import java.util.UUID;

/**
 * One executable referenced by a persisted block (ADR-027 D1/D2): a {@code core_time_block_member}
 * row joined to its executable's display name, re-read for the agenda write-back.
 *
 * <p>The block <b>references</b> the member, it never owns it: the executable stays the source of
 * truth of its own lifecycle and keeps its own Apple entity. The grouping does not nest — EventKit
 * exposes no subtasks (ADR-027 v2.1.0, docs#81) — so the member is listed in the container's note
 * while its own {@code EKReminder} remains individually checkable.
 *
 * @param executableId   the referenced executable; never null
 * @param name           the executable's display name, listed in the container's note; never blank
 * @param plannedMinutes this member's share of the container's duration; never negative
 * @param ord            the member's order inside the theme; never negative
 */
public record PlannedBlockMember(UUID executableId, String name, int plannedMinutes, int ord) {

    public PlannedBlockMember {
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (plannedMinutes < 0) {
            throw new IllegalArgumentException("plannedMinutes must not be negative: " + plannedMinutes);
        }
        if (ord < 0) {
            throw new IllegalArgumentException("ord must not be negative: " + ord);
        }
    }
}
