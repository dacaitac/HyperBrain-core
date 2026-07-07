package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonMap;

/*
 * Design pattern: Translator (a.k.a. Data Mapper)
 * Reason: isolates the domain → Notion representation rules in one pure, side-effect-free
 * class, so the write-back service and the tests share a single source of truth for the
 * attribute-by-attribute mapping (HU-10 field mapping table).
 */

/**
 * Maps an {@link ExecutableSnapshot} to the Notion Tasks property map (HU-10).
 *
 * <p>Mapping contract (issue #15 + ADR-012 D3 — the page is a full mirror of the row, so a
 * null domain value travels as an <b>explicit clear</b>, never as an omission):
 * <ul>
 *   <li>{@code name} → {@code Name} (title)</li>
 *   <li>{@code description} → {@code Description} (rich_text; blank → empty rich_text)</li>
 *   <li>{@code status} → {@code Status} (status) and {@code Complete} (checkbox, true iff DONE)</li>
 *   <li>{@code start_time}/{@code end_time} → {@code Date} (range, America/Bogota; date-only
 *       when the whole range falls on local midnight; both null → {@code date: null})</li>
 *   <li>{@code type} → {@code Type} (select)</li>
 *   <li>{@code priority_score} → {@code Priority Score}, {@code urgency_score} → {@code Urgence},
 *       {@code effort_score} → {@code Effort}, {@code frequency} → {@code Frequency}
 *       (numbers; null → {@code number: null})</li>
 *   <li>{@code is_important} → {@code Important} (checkbox)</li>
 *   <li>{@code impact}/{@code energy_drain}/{@code mental_load} (execution profile) →
 *       {@code Impact}/{@code Energy}/{@code Mental Load} (selects, Spanish canonical options;
 *       null → {@code select: null})</li>
 *   <li>{@code cycle_id} → {@code Cycle}, {@code parent_id} → {@code Parent Task} (relations;
 *       unresolved or absent → empty relation list)</li>
 * </ul>
 *
 * <p>The property map is canonical: it always contains every writable property in a fixed
 * order, which keeps the HU-14 echo checksums projection-invariant. Read-only formula/rollup
 * properties are never produced; {@link NotionSchema#assertWritable} guards the output (CA-9).
 * Thread-safe: stateless, static methods only.
 */
public final class NotionTaskMapper {

    private static final ZoneId NOTION_ZONE = ZoneId.of("America/Bogota");

    /** Notion caps title / rich_text content items at 2000 characters. */
    private static final int MAX_TEXT_LENGTH = 2000;

    private static final Map<String, String> STATUS_TO_NOTION = Map.of(
        "TODO", "Not started",
        "PLANNED", "Not started",
        "WAITING", "Not started",
        "IN_PROGRESS", "In progress",
        "DONE", "Done",
        "FAILED", "Failed");

    private static final Map<String, String> TYPE_TO_NOTION = Map.of(
        "TASK", "Task",
        "HABIT", "Habit",
        "LEAD_MEASURE", "Lead Measure",
        "ACTIVITY", "Activity",
        "AGENDA", "Agenda",
        "LEARNING_SESSION", "Learning Session");

    private NotionTaskMapper() {
    }

    /**
     * Builds the Notion Tasks property map for one executable. The map is a full mirror of
     * the row (ADR-012 D3): a null domain value produces an explicit clear (empty rich_text,
     * {@code date: null}, {@code number: null}, {@code select: null}, empty relation list),
     * so the page never keeps data the source of truth no longer has.
     *
     * @param snapshot         the executable state to propagate
     * @param cycleExternalId  Notion page id of the owning cycle, or null when unmapped
     * @param parentExternalId Notion page id of the parent task, or null when unmapped
     * @return an insertion-ordered property map, guaranteed free of read-only properties
     */
    public static Map<String, Object> map(ExecutableSnapshot snapshot,
                                          String cycleExternalId,
                                          String parentExternalId) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(NotionSchema.PROP_NAME, title(snapshot.name()));
        props.put(NotionSchema.PROP_DESCRIPTION,
            snapshot.description() != null && !snapshot.description().isBlank()
                ? richText(snapshot.description())
                : Map.of("rich_text", List.of()));
        props.put(NotionSchema.PROP_STATUS, status(mapStatus(snapshot.status())));
        props.put(NotionSchema.PROP_COMPLETE, checkbox("DONE".equals(snapshot.status())));
        props.put(NotionSchema.PROP_TYPE, select(mapType(snapshot.type())));
        Map<String, Object> date = dateRange(snapshot.startTime(), snapshot.endTime());
        props.put(NotionSchema.PROP_DATE, date != null ? date : singletonMap("date", null));
        putNumber(props, NotionSchema.PROP_PRIORITY_SCORE, snapshot.priorityScore());
        putNumber(props, NotionSchema.PROP_URGENCE, snapshot.urgencyScore());
        putNumber(props, NotionSchema.PROP_EFFORT, snapshot.effortScore());
        props.put(NotionSchema.PROP_IMPORTANT, checkbox(Boolean.TRUE.equals(snapshot.isImportant())));
        putNumber(props, NotionSchema.PROP_FREQUENCY, snapshot.frequency());
        putScale(props, NotionSchema.PROP_IMPACT, snapshot.impact(), NotionSchema.IMPACT_OPTIONS);
        putScale(props, NotionSchema.PROP_ENERGY, snapshot.energyDrain(), NotionSchema.ENERGY_OPTIONS);
        putScale(props, NotionSchema.PROP_MENTAL_LOAD, snapshot.mentalLoad(), NotionSchema.MENTAL_LOAD_OPTIONS);
        props.put(NotionSchema.PROP_CYCLE,
            cycleExternalId != null ? relation(cycleExternalId) : Map.of("relation", List.of()));
        props.put(NotionSchema.PROP_PARENT_TASK,
            parentExternalId != null ? relation(parentExternalId) : Map.of("relation", List.of()));
        NotionSchema.assertWritable(props);
        return props;
    }

    /** Projects a domain status to its Notion option name (lossy; shared with the merge). */
    static String mapStatus(String domainStatus) {
        String notionStatus = domainStatus != null ? STATUS_TO_NOTION.get(domainStatus) : null;
        return notionStatus != null ? notionStatus : "Not started";
    }

    /** Projects a domain type to its Notion option name (shared with the merge). */
    static String mapType(String domainType) {
        String notionType = domainType != null ? TYPE_TO_NOTION.get(domainType) : null;
        return notionType != null ? notionType : "Task";
    }

    /** Truncates to the Notion 2000-char content limit — the text projection for the merge. */
    static String projectText(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > MAX_TEXT_LENGTH ? content.substring(0, MAX_TEXT_LENGTH) : content;
    }

    private static void putNumber(Map<String, Object> props, String name, Double value) {
        props.put(name, value != null ? Map.of("number", value) : singletonMap("number", null));
    }

    /**
     * Maps a 1-based numeric scale onto its select options, clamping overflow to the top
     * option; a null scale clears the select (full-mirror contract, ADR-012 D3).
     */
    private static void putScale(Map<String, Object> props, String name, Integer value,
                                 List<String> options) {
        if (value == null) {
            props.put(name, singletonMap("select", null));
            return;
        }
        int index = Math.min(Math.max(value, 1), options.size()) - 1;
        props.put(name, select(options.get(index)));
    }

    // ── Notion property value builders (shared with the cycle mapper) ─────────

    static Map<String, Object> title(String content) {
        return Map.of("title", List.of(text(content)));
    }

    static Map<String, Object> richText(String content) {
        return Map.of("rich_text", List.of(text(content)));
    }

    static Map<String, Object> select(String optionName) {
        return Map.of("select", Map.of("name", optionName));
    }

    static Map<String, Object> status(String optionName) {
        return Map.of("status", Map.of("name", optionName));
    }

    static Map<String, Object> checkbox(boolean value) {
        return Map.of("checkbox", value);
    }

    static Map<String, Object> relation(String externalPageId) {
        return Map.of("relation", List.of(Map.of("id", externalPageId)));
    }

    static Map<String, Object> dateValue(String start, String end) {
        Map<String, Object> date = new LinkedHashMap<>();
        date.put("start", start);
        if (end != null) {
            date.put("end", end);
        }
        return Map.of("date", date);
    }

    private static Map<String, Object> text(String content) {
        String safe = content != null ? content : "";
        if (safe.length() > MAX_TEXT_LENGTH) {
            safe = safe.substring(0, MAX_TEXT_LENGTH);
        }
        return Map.of("text", Map.of("content", safe));
    }

    /**
     * Formats the start/end pair as a Notion date range in America/Bogota. When every bound
     * falls exactly on local midnight the range degrades to date-only values, so all-day
     * entities render as all-day in Notion.
     */
    private static Map<String, Object> dateRange(OffsetDateTime start, OffsetDateTime end) {
        if (start == null && end == null) {
            return null;
        }
        OffsetDateTime effectiveStart = start != null ? start : end;
        OffsetDateTime localStart = effectiveStart.atZoneSameInstant(NOTION_ZONE).toOffsetDateTime();
        OffsetDateTime localEnd = (start != null && end != null)
            ? end.atZoneSameInstant(NOTION_ZONE).toOffsetDateTime()
            : null;
        boolean dateOnly = isMidnight(localStart) && (localEnd == null || isMidnight(localEnd));
        if (dateOnly) {
            return dateValue(localStart.toLocalDate().toString(),
                localEnd != null ? localEnd.toLocalDate().toString() : null);
        }
        return dateValue(localStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            localEnd != null ? localEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null);
    }

    private static boolean isMidnight(OffsetDateTime dt) {
        return dt.getHour() == 0 && dt.getMinute() == 0 && dt.getSecond() == 0 && dt.getNano() == 0;
    }
}
