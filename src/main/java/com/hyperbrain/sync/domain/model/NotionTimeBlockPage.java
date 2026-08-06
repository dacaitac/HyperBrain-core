package com.hyperbrain.sync.domain.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Property-level view of one Notion <b>Time Blocks</b> database page (ADR-038), parsed by
 * {@code NotionPageParser}. Date bounds and relation ids are kept raw — the inbound sync owns
 * the interpretation (loss-aware projection, membership mapping).
 *
 * @param pageId              normalized page id
 * @param lastEditedTime      Notion's minute-truncated edit timestamp; may be null
 * @param archived            whether the page is archived or trashed
 * @param title               the composed title ({@code Mié 05 · 09:00–10:30 · theme}); may be null
 * @param dateStart           raw {@code Date.start} bound; may be null
 * @param dateEnd             raw {@code Date.end} bound; may be null
 * @param statusName          the {@code Status} select option; may be null
 * @param originName          the {@code Origin} select option; may be null
 * @param plannedMinutes      the {@code Planned Minutes} number; may be null
 * @param actualMinutes       the {@code Actual Minutes} number; may be null
 * @param taskRelationIds     normalized page ids of the {@code Tasks} relation, in page order
 * @param taskRelationHasMore true when Notion truncated the relation array ({@code has_more}) —
 *                            the membership view is incomplete and must not be applied (CA guard)
 * @param syncNote            the {@code Sync Note} rich text; may be null
 */
public record NotionTimeBlockPage(
    String pageId,
    OffsetDateTime lastEditedTime,
    boolean archived,
    String title,
    String dateStart,
    String dateEnd,
    String statusName,
    String originName,
    Double plannedMinutes,
    Double actualMinutes,
    List<String> taskRelationIds,
    boolean taskRelationHasMore,
    String syncNote
) {

    public NotionTimeBlockPage {
        taskRelationIds = List.copyOf(taskRelationIds);
    }
}
