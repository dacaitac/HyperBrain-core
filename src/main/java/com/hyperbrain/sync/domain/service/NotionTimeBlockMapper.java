package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.singletonMap;

/*
 * Design pattern: Translator (a.k.a. Data Mapper)
 * Reason: isolates the domain → Notion representation rules of the Time Blocks mirror in one
 * pure, side-effect-free class shared by the propagator, the inbound diff and the tests
 * (ADR-038), exactly like NotionTaskMapper does for the Tasks database.
 */

/**
 * Maps a {@link TimeBlockSnapshot} to the Notion Time Blocks property map (ADR-038).
 *
 * <p>Mapping contract (full-mirror, ADR-012 D3 — a null domain value travels as an explicit
 * clear, never as an omission):
 * <ul>
 *   <li>composed title → {@code Name}: {@code Mié 05 · 09:00–10:30 · <theme>} (user-local day,
 *       theme falling back to the anchor member's name);</li>
 *   <li>{@code date_start}/{@code date_end} → {@code Date} (range, America/Bogota);</li>
 *   <li>{@code status} → {@code Status} (select Planned/Active/Settled/Expired);</li>
 *   <li>{@code origin} → {@code Origin} (select Planner/User; FOCUS blocks are never mirrored);</li>
 *   <li>{@code planned_minutes}/{@code actual_duration_minutes} → numbers;</li>
 *   <li>members → {@code Agenda} (derived rich text {@code 1. A — 30 min · 2. B — 45 min},
 *       {@code ord} ascending) and {@code Tasks} (relation in <b>canonical order</b> — page ids
 *       sorted ascending — so the anti-echo checksum is stable under Notion's arbitrary relation
 *       ordering);</li>
 *   <li>{@code reason} → {@code Reason}; the caller-provided sync note → {@code Sync Note}
 *       (empty rich text when null).</li>
 * </ul>
 *
 * <p>The read-only {@code Cycles} rollup is never produced; {@link NotionSchema#assertWritable}
 * guards the output. Thread-safe: stateless, static methods only.
 */
public final class NotionTimeBlockMapper {

    private static final ZoneId NOTION_ZONE = ZoneId.of("America/Bogota");

    private static final DateTimeFormatter DAY_FORMAT =
        DateTimeFormatter.ofPattern("EEE dd", Locale.forLanguageTag("es"));
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final String TITLE_SEPARATOR = " · ";

    /**
     * The composed-title shape: {@code <day token> · HH:mm–HH:mm · <theme>}. Used to extract the
     * theme part of an inbound title; a title that no longer matches is treated as all-theme
     * (the user rewrote it entirely).
     */
    private static final Pattern COMPOSED_TITLE = Pattern.compile(
        "^\\p{L}{2,4}\\.? \\d{2} · \\d{2}:\\d{2}–\\d{2}:\\d{2} · (.+)$", Pattern.DOTALL);

    private static final Map<String, String> STATUS_TO_NOTION = Map.of(
        "PLANNED", "Planned",
        "ACTIVE", "Active",
        "SETTLED", "Settled",
        "EXPIRED", "Expired");

    private static final Map<String, String> ORIGIN_TO_NOTION = Map.of(
        "PLANNER", "Planner",
        "USER", "User");

    private NotionTimeBlockMapper() {
    }

    /**
     * Builds the canonical Notion Time Blocks property map for one block. The map always
     * contains every writable property in a fixed order, and the {@code Tasks} relation in
     * canonical (sorted) order, which keeps the anti-echo checksum projection-invariant in both
     * directions.
     *
     * @param snapshot    the block state to mirror
     * @param taskPageIds Notion page ids of the mapped members (unmapped members omitted,
     *                    CA-6 pattern); any order — canonicalized here
     * @param syncNote    visible conflict note to show on the page, or null to clear it
     * @return an insertion-ordered property map, guaranteed free of read-only properties
     */
    public static Map<String, Object> map(TimeBlockSnapshot snapshot, List<String> taskPageIds,
                                          String syncNote) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(NotionSchema.PROP_NAME, NotionTaskMapper.title(composeTitle(snapshot)));
        props.put(NotionSchema.PROP_DATE, dateRange(snapshot.dateStart(), snapshot.dateEnd()));
        props.put(NotionSchema.PROP_STATUS,
            NotionTaskMapper.select(mapStatus(snapshot.status())));
        props.put(NotionSchema.PROP_ORIGIN,
            NotionTaskMapper.select(mapOrigin(snapshot.origin())));
        putNumber(props, NotionSchema.PROP_PLANNED_MINUTES, snapshot.plannedMinutes());
        putNumber(props, NotionSchema.PROP_ACTUAL_MINUTES, snapshot.actualDurationMinutes());
        props.put(NotionSchema.PROP_AGENDA, richTextOrEmpty(agendaLine(snapshot.members())));
        props.put(NotionSchema.PROP_REASON, richTextOrEmpty(snapshot.reason()));
        props.put(NotionSchema.PROP_SYNC_NOTE, richTextOrEmpty(syncNote));
        props.put(NotionSchema.PROP_TASKS, relationList(taskPageIds));
        NotionSchema.assertWritable(props);
        return props;
    }

    /**
     * Composes the mirror title {@code Mié 05 · 09:00–10:30 · <theme>}: capitalized Spanish
     * short day-of-week plus day-of-month, the local time window, and the theme (falling back
     * to the anchor member's name, ADR-027 D1).
     */
    public static String composeTitle(TimeBlockSnapshot snapshot) {
        OffsetDateTime localStart = snapshot.dateStart().atZoneSameInstant(NOTION_ZONE)
            .toOffsetDateTime();
        OffsetDateTime end = snapshot.dateEnd() != null ? snapshot.dateEnd() : snapshot.dateStart();
        OffsetDateTime localEnd = end.atZoneSameInstant(NOTION_ZONE).toOffsetDateTime();
        String day = capitalizeDayToken(DAY_FORMAT.format(localStart));
        return day + TITLE_SEPARATOR
            + TIME_FORMAT.format(localStart) + "–" + TIME_FORMAT.format(localEnd)
            + TITLE_SEPARATOR + snapshot.themeOrAnchorName();
    }

    /**
     * Extracts the theme part of an inbound composed title. A title that does not match the
     * composed shape is returned whole — the user replaced the title entirely, and all of it is
     * their theme.
     *
     * @param title the page title; may be null
     * @return the theme text, or null when the title is blank
     */
    public static String extractTheme(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        Matcher matcher = COMPOSED_TITLE.matcher(title.strip());
        return matcher.matches() ? matcher.group(1).strip() : title.strip();
    }

    /**
     * Derives the {@code Agenda} line: {@code 1. Task A — 30 min · 2. Task B — 45 min}, members
     * in {@code ord} ascending order. Null when the block carries no members.
     */
    public static String agendaLine(List<TimeBlockMemberSnapshot> members) {
        if (members.isEmpty()) {
            return null;
        }
        List<TimeBlockMemberSnapshot> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(TimeBlockMemberSnapshot::ord));
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                line.append(TITLE_SEPARATOR);
            }
            TimeBlockMemberSnapshot member = ordered.get(index);
            line.append(index + 1).append(". ").append(member.name())
                .append(" — ").append(member.plannedMinutes()).append(" min");
        }
        return line.toString();
    }

    /** Projects a domain block status to its Notion option name (shared with the inbound). */
    public static String mapStatus(String domainStatus) {
        String notionStatus = domainStatus != null ? STATUS_TO_NOTION.get(domainStatus) : null;
        return notionStatus != null ? notionStatus : "Planned";
    }

    /** Projects a domain block origin to its Notion option name (FOCUS is never mirrored). */
    public static String mapOrigin(String domainOrigin) {
        String notionOrigin = domainOrigin != null ? ORIGIN_TO_NOTION.get(domainOrigin) : null;
        return notionOrigin != null ? notionOrigin : "Planner";
    }

    /**
     * A single-property patch for the {@code Sync Note} field — the visibility channel of a
     * page that has no domain counterpart to mirror (a Notion-side draft, ADR-038): nothing
     * else on the page is touched.
     *
     * @param note the visible note; never null
     * @return a property map carrying only the Sync Note
     */
    public static Map<String, Object> syncNoteProperty(String note) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(NotionSchema.PROP_SYNC_NOTE, NotionTaskMapper.richText(note));
        NotionSchema.assertWritable(props);
        return props;
    }

    /**
     * The canonical relation value: page ids sorted ascending, so the same member set always
     * serializes identically regardless of the order Notion (or the caller) reports it in —
     * the checksum contract of ADR-038.
     */
    static Map<String, Object> relationList(List<String> taskPageIds) {
        List<Map<String, Object>> relations = taskPageIds.stream()
            .sorted()
            .<Map<String, Object>>map(id -> Map.of("id", id))
            .toList();
        return Map.of("relation", relations);
    }

    private static Map<String, Object> dateRange(OffsetDateTime start, OffsetDateTime end) {
        if (start == null) {
            return singletonMap("date", null);
        }
        String localStart = start.atZoneSameInstant(NOTION_ZONE).toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String localEnd = end != null && end.isAfter(start)
            ? end.atZoneSameInstant(NOTION_ZONE).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            : null;
        return NotionTaskMapper.dateValue(localStart, localEnd);
    }

    private static void putNumber(Map<String, Object> props, String name, Integer value) {
        props.put(name,
            value != null ? Map.of("number", value) : singletonMap("number", null));
    }

    private static Map<String, Object> richTextOrEmpty(String content) {
        return content != null && !content.isBlank()
            ? NotionTaskMapper.richText(content)
            : Map.of("rich_text", List.of());
    }

    /** {@code "mié. 05"} → {@code "Mié 05"}: strip the locale dot, capitalize the first letter. */
    private static String capitalizeDayToken(String formatted) {
        String clean = formatted.replace(".", "");
        return clean.isEmpty()
            ? clean
            : Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }
}
