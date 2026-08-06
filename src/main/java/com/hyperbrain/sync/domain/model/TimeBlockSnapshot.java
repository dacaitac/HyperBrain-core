package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a {@code core_time_block} row (joined to its owner and members) for the Notion
 * Time Blocks mirror (ADR-038). Sibling of {@link ExecutableSnapshot}/{@link CycleSnapshot}: the
 * block aggregate itself is owned by the planner — this is only the sync-facing projection the
 * write-back mirrors, never a second domain model.
 *
 * @param id                    the {@code core_time_block.id}
 * @param userId                the owning user (via the anchor executable)
 * @param dateStart             block window start; never null
 * @param dateEnd               block window end; may be null (auto-opened FOCUS blocks)
 * @param status                lifecycle state ({@code PLANNED}, {@code ACTIVE}, {@code SETTLED},
 *                              {@code EXPIRED})
 * @param origin                who opened the block ({@code PLANNER}, {@code FOCUS}, {@code USER})
 * @param theme                 the themed container's name; may be null (floor-derived)
 * @param reason                the readable placement reason; may be null
 * @param plannedMinutes        planned duration; may be null
 * @param actualDurationMinutes gross executed minutes frozen on settlement; may be null
 * @param settledAt             settlement instant; null while the block is open
 * @param members               the block's members in {@code ord} order; never null, may be empty
 *                              for legacy rows whose membership lives only on the anchor column
 */
public record TimeBlockSnapshot(
    UUID id,
    UUID userId,
    OffsetDateTime dateStart,
    OffsetDateTime dateEnd,
    String status,
    String origin,
    String theme,
    String reason,
    Integer plannedMinutes,
    Integer actualDurationMinutes,
    OffsetDateTime settledAt,
    List<TimeBlockMemberSnapshot> members
) {

    public TimeBlockSnapshot {
        members = List.copyOf(members);
    }

    /** @return true while the block is open ({@code PLANNED} or {@code ACTIVE}) */
    public boolean live() {
        return "PLANNED".equals(status) || "ACTIVE".equals(status);
    }

    /** @return true when the block is frozen history ({@code SETTLED} or {@code EXPIRED}) */
    public boolean terminal() {
        return "SETTLED".equals(status) || "EXPIRED".equals(status);
    }

    /**
     * The mirror title's theme part: the container's theme when named, else the anchor member's
     * name — the same fallback the Apple write-back uses (ADR-027 D1).
     *
     * @return the theme text; never blank when the block has at least one member
     */
    public String themeOrAnchorName() {
        if (theme != null && !theme.isBlank()) {
            return theme.strip();
        }
        return members.isEmpty() ? "" : members.get(0).name();
    }
}
