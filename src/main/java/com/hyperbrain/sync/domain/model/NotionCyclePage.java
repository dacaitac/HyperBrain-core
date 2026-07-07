package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;

/**
 * Property-level view of one page of the Notion Cycles database (HU-14, ADR-011), extracted
 * from a raw page object. Values are kept at the Notion representation level; the inbound
 * mapper turns them into a {@link CycleSnapshot}.
 *
 * @param pageId         Notion page id, normalized (lowercase, no dashes)
 * @param lastEditedTime {@code last_edited_time} of the page (monotonicity guard, CA-29)
 * @param archived       true when the page is {@code archived} or {@code in_trash} (CA-7)
 * @param name           plain text of the {@code Name} title property
 * @param typeName       {@code Type} select option name ({@code MCI}/{@code Routine}/{@code Phase})
 * @param dateStart      raw {@code Date.start} string, or null
 * @param dateEnd        raw {@code Date.end} string, or null
 * @param inactive       {@code Inactive} checkbox; true maps to status {@code COMPLETED}
 */
public record NotionCyclePage(
    String pageId,
    OffsetDateTime lastEditedTime,
    boolean archived,
    String name,
    String typeName,
    String dateStart,
    String dateEnd,
    Boolean inactive
) {
}
