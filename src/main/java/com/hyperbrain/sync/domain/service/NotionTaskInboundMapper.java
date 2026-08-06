package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*
 * Design pattern: Translator (a.k.a. Data Mapper)
 * Reason: exact inverse of NotionTaskMapper (HU-10) — one pure, side-effect-free class owns
 * the Notion → domain representation rules (HU-14 CA-5), so handlers, backfill and tests
 * share a single source of truth.
 */

/**
 * Maps a {@link NotionTaskPage} to an {@link ExecutableSnapshot} — the inverse of
 * {@link NotionTaskMapper} (HU-14 CA-5), with the merge rules inherited from the legacy:
 * <ul>
 *   <li>Completion follows the "either signal completes" policy (Option B): {@code DONE} when
 *       the {@code Complete} checkbox is checked <b>or</b> {@code Status=Done}; uncompleting
 *       needs both signals non-completed. See {@link #resolveStatus}.
 *   <li>Unknown {@code Status}/{@code Type} options degrade to {@code TODO}/{@code TASK}
 *       instead of failing — the schema may gain options before the Core learns them.
 *   <li>Scale selects ({@code Impact}/{@code Energy}/{@code Mental Load}) map to their
 *       1-based option index using the canonical lists in {@link NotionSchema}; unknown
 *       options map to null (never guessed).
 *   <li>Numeric scores are clamped to their DDL check ranges ({@code priority_score} to
 *       [0, 1], {@code effort_score} to [0, 5]) so a manual edit in Notion cannot poison
 *       the queue with a constraint violation.
 *   <li>Date-only values are anchored at America/Bogota midnight, mirroring the outbound
 *       date-only degradation.
 * </ul>
 *
 * <p>The domain status set is wider than Notion's ({@code PLANNED}/{@code WAITING} both
 * render as {@code Not started}); re-ingesting an unchanged page would therefore downgrade
 * them to {@code TODO} — that regression is prevented upstream by the checksum discard
 * (CA-4), not here. Thread-safe: stateless, static methods only.
 */
public final class NotionTaskInboundMapper {

    private static final ZoneId NOTION_ZONE = ZoneId.of("America/Bogota");

    static final Map<String, String> STATUS_FROM_NOTION = Map.of(
        "Not started", "TODO",
        "In progress", "IN_PROGRESS",
        "Done", "DONE",
        "Failed", "FAILED");

    static final Map<String, String> TYPE_FROM_NOTION = Map.ofEntries(
        Map.entry("Task", "TASK"),
        Map.entry("Habit", "HABIT"),
        Map.entry("Lead Measure", "LEAD_MEASURE"),
        Map.entry("Activity", "ACTIVITY"),
        Map.entry("Agenda", "AGENDA"),
        Map.entry("Learning Session", "LEARNING_SESSION"),
        Map.entry("Buying", "BUYING"),
        // ADR-039: TIME_BLOCK lives in the Tasks DB as a Type option.
        Map.entry("Time Block", "TIME_BLOCK"));

    private NotionTaskInboundMapper() {
    }

    /**
     * Builds the executable snapshot for one Notion Tasks page — the CREATE interpretation
     * of the page (ADR-012: updates go through {@code SourceAwareMerge}, which reuses these
     * rules field by field against the current row). Relations arrive already resolved to
     * local ids by the caller (Notion is the planning authority: {@code cycle} and
     * {@code parent} are always accepted from Notion, CA-6). {@code source_calendar} is
     * Apple authority and always null here.
     *
     * @param page     the parsed page properties
     * @param id       local {@code core_executable} id (existing mapping or a fresh UUID)
     * @param userId   owning user (single-user MVP)
     * @param cycleId  resolved local cycle id, or null
     * @param parentId resolved local parent executable id, or null
     * @return the snapshot to persist
     */
    public static ExecutableSnapshot toSnapshot(NotionTaskPage page, UUID id, UUID userId,
                                                UUID cycleId, UUID parentId) {
        return new ExecutableSnapshot(
            id,
            userId,
            parentId,
            cycleId,
            page.name() != null ? page.name() : "",
            blankToNull(page.description()),
            mapType(page.typeName()),
            resolveStatus(page.statusName(), page.complete()),
            clamp(page.priorityScore(), 0.0, 1.0),
            page.urgencyScore(),
            clamp(page.effortScore(), 0.0, 5.0),
            Boolean.TRUE.equals(page.important()),
            page.frequency(),
            parseNotionDate(page.dateStart()),
            parseNotionDate(page.dateEnd()),
            null,
            scaleOf(page.energyName(), NotionSchema.ENERGY_OPTIONS),
            scaleOf(page.mentalLoadName(), NotionSchema.MENTAL_LOAD_OPTIONS),
            scaleOf(page.impactName(), NotionSchema.IMPACT_OPTIONS),
            false);
    }

    /**
     * Resolves the domain status under the ADR-039 <b>Status-first for FAILED</b> policy (E1):
     * an explicit {@code Status = Failed} is <b>first-class</b> and is never collapsed to
     * {@code DONE} — the sanctioned-miss terminal wins over the {@code Complete} checkbox. For
     * every other case the Option-B "either signal completes" rule is unchanged: a checked
     * {@code Complete} box (or {@code Status = Done}) completes to {@code DONE}, otherwise the
     * {@code Status} option maps directly ({@code In progress} → {@code IN_PROGRESS},
     * {@code Not started}/unknown/null → {@code TODO}). Uncompleting is handled upstream by
     * {@link SourceAwareMerge#mergeStatus} (Complete-authoritative reopen, extended to FAILED).
     */
    static String resolveStatus(String statusName, Boolean complete) {
        String mapped = statusName != null ? STATUS_FROM_NOTION.get(statusName) : null;
        // FAILED is first-class (ADR-039): an explicit Failed is never overridden by the checkbox.
        if ("FAILED".equals(mapped)) {
            return "FAILED";
        }
        // Option B: the checkbox (or Status=Done) completes to DONE for any non-Failed row.
        if (Boolean.TRUE.equals(complete)) {
            return "DONE";
        }
        return mapped != null ? mapped : "TODO";
    }

    static String mapType(String typeName) {
        String mapped = typeName != null ? TYPE_FROM_NOTION.get(typeName) : null;
        return mapped != null ? mapped : "TASK";
    }

    /** Returns the 1-based index of the option in its canonical list, or null when unknown. */
    static Integer scaleOf(String optionName, List<String> options) {
        if (optionName == null) {
            return null;
        }
        int index = options.indexOf(optionName);
        return index >= 0 ? index + 1 : null;
    }

    /**
     * Parses a Notion date value: date-only strings anchor at America/Bogota midnight
     * (mirror of the outbound date-only degradation); unparseable values map to null.
     */
    static OffsetDateTime parseNotionDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay(NOTION_ZONE).toOffsetDateTime();
            }
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    static Double clamp(Double value, double min, double max) {
        if (value == null) {
            return null;
        }
        return Math.min(Math.max(value, min), max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
