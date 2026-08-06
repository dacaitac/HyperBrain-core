package com.hyperbrain.sync.domain.model;

import java.util.UUID;

/**
 * One member of a mirrored time block (ADR-038): a {@code core_time_block_member} row joined to
 * its executable's display name for the Notion {@code Agenda} projection.
 *
 * @param executableId   the referenced executable; never null
 * @param name           the executable's display name; never blank
 * @param plannedMinutes this member's share of the container's duration
 * @param ord            the member's order inside the theme
 */
public record TimeBlockMemberSnapshot(UUID executableId, String name, int plannedMinutes, int ord) {
}
