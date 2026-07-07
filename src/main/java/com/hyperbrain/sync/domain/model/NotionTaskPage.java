package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;

/**
 * Property-level view of one page of the Notion Tasks database (HU-14), extracted from a raw
 * page object (webhook automation payload, API fetch or backfill query). Values are kept at
 * the Notion representation level (option names, raw date strings); the inbound mapper turns
 * them into domain values.
 *
 * @param pageId           Notion page id, normalized (lowercase, no dashes) — the
 *                         {@code sync_mappings.external_id}
 * @param lastEditedTime   {@code last_edited_time} of the page; drives the monotonicity guard
 *                         (CA-29); may be null on malformed payloads
 * @param archived         true when the page is {@code archived} or {@code in_trash} — always
 *                         processed as DELETED, never as an update (CA-7)
 * @param name             plain text of the {@code Name} title property
 * @param description      plain text of the {@code Description} rich text, or null
 * @param statusName       {@code Status} option name, or null
 * @param complete         {@code Complete} checkbox; has priority over {@code Status} when
 *                         resolving the domain status
 * @param typeName         {@code Type} select option name, or null
 * @param dateStart        raw {@code Date.start} string (date or datetime), or null
 * @param dateEnd          raw {@code Date.end} string, or null
 * @param priorityScore    {@code Priority Score} number, or null
 * @param urgencyScore     {@code Urgence} number, or null
 * @param effortScore      {@code Effort} number, or null
 * @param important        {@code Important} checkbox, or null when the property is absent
 * @param frequency        {@code Frequency} number, or null
 * @param impactName       {@code Impact} select option name, or null
 * @param energyName       {@code Energy} select option name, or null
 * @param mentalLoadName   {@code Mental Load} select option name, or null
 * @param cycleRelationId  first {@code Cycle} relation page id, normalized, or null
 * @param parentRelationId first {@code Parent Task} relation page id, normalized, or null
 */
public record NotionTaskPage(
    String pageId,
    OffsetDateTime lastEditedTime,
    boolean archived,
    String name,
    String description,
    String statusName,
    Boolean complete,
    String typeName,
    String dateStart,
    String dateEnd,
    Double priorityScore,
    Double urgencyScore,
    Double effortScore,
    Boolean important,
    Double frequency,
    String impactName,
    String energyName,
    String mentalLoadName,
    String cycleRelationId,
    String parentRelationId
) {
}
