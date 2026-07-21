package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;

/**
 * Authorship view of a Notion page (the {@code last_edited_by} / {@code last_edited_time} pair),
 * used by the outbound staleness guard to decide whether a write-back would clobber a human edit
 * still in flight. Kept minimal on purpose: the guard only needs the actor and the (minute-
 * truncated) edit timestamp, never the full property view.
 *
 * @param lastEditedById id of the Notion user who last edited the page (bot or person),
 *                       normalized (lowercase, no dashes), or null when absent
 * @param lastEditedTime page {@code last_edited_time}; Notion truncates it to the minute, or null
 *                       on a malformed payload
 */
public record NotionPageEditState(
    String lastEditedById,
    OffsetDateTime lastEditedTime
) {
}
