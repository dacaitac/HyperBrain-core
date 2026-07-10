package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import com.hyperbrain.sync.domain.model.ReminderPayload;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/*
 * Design pattern: Policy (pure domain service)
 * Reason: ADR-012 D1 is one write policy shared by every inbound source; keeping the
 * authority matrix and the loss-aware projection rule in a single stateless class lets the
 * Apple and Notion handlers apply identical semantics and the tests exercise the policy
 * without Spring or I/O.
 */

/**
 * Field-level merge of an inbound external state onto the current {@code core_executable}
 * row (ADR-012 D1). Ingestion never overwrites the full row; two rules apply:
 *
 * <ol>
 *   <li><b>Authority matrix</b> — each source only writes the fields its system represents.
 *       Apple: name, notes, due date, start/end (calendar events only), the completed flag and
 *       the containing list/calendar. Notion: the HU-10 mapping (including {@code Important}
 *       and {@code Frequency}) plus the planning relations (CA-6). Everything else keeps its
 *       current value — no external source ever touches a computed field.
 *   <li><b>Loss-aware projection</b> — before applying a field, the current domain value is
 *       projected to the external representation (with the same outbound mapper rules); when
 *       the projection equals what arrived, the field is unchanged and the domain value is
 *       kept. This is what stops {@code Not started} from regressing {@code PLANNED}/{@code
 *       WAITING} to {@code TODO}, an untouched Apple reminder from regressing
 *       {@code IN_PROGRESS}, and a 2000-char truncated text from clobbering the full one.
 * </ol>
 *
 * <p>A null {@code current} means the entity is new: the incoming state is interpreted with
 * the plain CREATE rules ({@link NotionTaskInboundMapper} for Notion). Thread-safe:
 * stateless, static methods only.
 */
public final class SourceAwareMerge {

    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_TODO = "TODO";

    private SourceAwareMerge() {
    }

    /**
     * Merges an Apple REMINDER state onto the current row. Apple authority: name, notes,
     * due date (stored as {@code start_time} — DR-01 forbids {@code end_time} on TASK),
     * completed flag, containing list.
     *
     * <p>The due date is compared against the outbound projection {@code end_time ?? start_time}
     * so that an untouched due date never writes to the DB. When the due date changes, Apple
     * takes authority over {@code start_time}; when it is unchanged, the existing
     * {@code start_time} is preserved (protecting any Notion-owned value set by the user).
     *
     * @param current the current row joined with its profile, or null when unmapped
     * @param id      local executable id (existing mapping or a fresh UUID)
     * @param userId  owning user
     * @param p       the inbound reminder payload
     * @return the merged state to process and persist
     */
    public static ExecutableSnapshot mergeReminder(ExecutableSnapshot current, UUID id,
                                                   UUID userId, ReminderPayload p) {
        if (current == null) {
            return new ExecutableSnapshot(id, userId, null, null,
                p.title(), p.notes(), "TASK", p.completed() ? STATUS_DONE : STATUS_TODO,
                null, null, null, false, null,
                p.dueDate(), null, p.listName(), null, null, null, false);
        }
        OffsetDateTime dueProjection = current.endTime() != null
            ? current.endTime()
            : current.startTime();
        OffsetDateTime newStartTime = sameInstant(p.dueDate(), dueProjection)
            ? current.startTime()  // due date unchanged: preserve existing startTime
            : p.dueDate();         // due date changed: Apple takes authority over startTime
        return new ExecutableSnapshot(id, userId, current.parentId(), current.cycleId(),
            p.title(), p.notes(), current.type(), mergeCompletedFlag(current.status(), p.completed()),
            current.priorityScore(), current.urgencyScore(), current.effortScore(),
            current.isImportant(), current.frequency(),
            newStartTime, null, p.listName(),
            current.energyDrain(), current.mentalLoad(), current.impact(),
            current.systemGenerated());
    }

    /**
     * Merges an Apple CALENDAR_EVENT state onto the current row. Apple authority: name,
     * notes, start/end and the containing calendar. The status is kept (EventKit events have
     * no completed flag — the old pipeline hard-reset it to {@code TODO}) and so is the type
     * ({@code AGENDA} stays {@code AGENDA}, ADR-009).
     *
     * @param current the current row joined with its profile, or null when unmapped
     * @param id      local executable id (existing mapping or a fresh UUID)
     * @param userId  owning user
     * @param p       the inbound calendar event payload
     * @return the merged state to process and persist
     */
    public static ExecutableSnapshot mergeCalendarEvent(ExecutableSnapshot current, UUID id,
                                                        UUID userId, CalendarEventPayload p) {
        if (current == null) {
            return new ExecutableSnapshot(id, userId, null, null,
                p.title(), p.notes(), "ACTIVITY", STATUS_TODO,
                null, null, null, false, null,
                p.startTime(), p.endTime(), p.calendarName(), null, null, null, false);
        }
        return new ExecutableSnapshot(id, userId, current.parentId(), current.cycleId(),
            p.title(), p.notes(), current.type(), current.status(),
            current.priorityScore(), current.urgencyScore(), current.effortScore(),
            current.isImportant(), current.frequency(),
            p.startTime(), p.endTime(), p.calendarName(),
            current.energyDrain(), current.mentalLoad(), current.impact(),
            current.systemGenerated());
    }

    /**
     * Merges a Notion Tasks page state onto the current row. Notion authority: the full
     * HU-10 mapping plus the planning relations (CA-6); {@code source_calendar} and any
     * computed field outside the mapping keep their current value. Lossy projections
     * (status, type, truncated texts) only apply when the arriving value differs from the
     * projection of the current one; a known relation that is not mapped yet keeps the
     * current link instead of destroying it (the next webhook repairs it).
     *
     * @param current          the current row joined with its profile, or null when unmapped
     * @param page             the parsed page
     * @param id               local executable id (existing mapping or a fresh UUID)
     * @param userId           owning user
     * @param resolvedCycleId  local cycle id resolved by the caller, or null (no relation or
     *                         unresolvable cycle)
     * @param resolvedParentId local parent id resolved by the caller, or null (no relation or
     *                         parent page not mapped yet)
     * @return the merged state to process and persist
     */
    public static ExecutableSnapshot mergeNotionTask(ExecutableSnapshot current, NotionTaskPage page,
                                                     UUID id, UUID userId,
                                                     UUID resolvedCycleId, UUID resolvedParentId) {
        if (current == null) {
            return NotionTaskInboundMapper.toSnapshot(page, id, userId,
                resolvedCycleId, resolvedParentId);
        }
        UUID parentId = page.parentRelationId() == null
            ? null
            : (resolvedParentId != null ? resolvedParentId : current.parentId());
        String name = mergeText(current.name(), page.name());
        return new ExecutableSnapshot(id, userId, parentId, resolvedCycleId,
            name != null ? name : "",
            mergeText(current.description(), page.description()),
            mergeType(current.type(), page.typeName()),
            mergeStatus(current.status(), page.statusName(), page.complete()),
            mergeNumber(current.priorityScore(), NotionTaskInboundMapper.clamp(page.priorityScore(), 0.0, 1.0)),
            mergeNumber(current.urgencyScore(), page.urgencyScore()),
            mergeNumber(current.effortScore(), NotionTaskInboundMapper.clamp(page.effortScore(), 0.0, 5.0)),
            page.important() != null ? page.important() : current.isImportant(),
            mergeNumber(current.frequency(), page.frequency()),
            mergeInstant(current.startTime(), page.dateStart()),
            mergeInstant(current.endTime(), page.dateEnd()),
            current.sourceCalendar(),
            mergeScale(current.energyDrain(), page.energyName(), NotionSchema.ENERGY_OPTIONS),
            mergeScale(current.mentalLoad(), page.mentalLoadName(), NotionSchema.MENTAL_LOAD_OPTIONS),
            mergeScale(current.impact(), page.impactName(), NotionSchema.IMPACT_OPTIONS),
            current.systemGenerated());
    }

    /**
     * Applies the Apple completed flag with the loss-aware rule: {@code completed=false}
     * projects from every non-DONE status, so it only regresses an actual {@code DONE}.
     */
    static String mergeCompletedFlag(String currentStatus, boolean completed) {
        if (completed) {
            return STATUS_DONE;
        }
        return STATUS_DONE.equals(currentStatus) ? STATUS_TODO : currentStatus;
    }

    /**
     * Applies a Notion status pair with the loss-aware rule: when the current status projects
     * to the same {@code Status} option and the same {@code Complete} flag, nothing changed
     * in Notion and the (richer) domain status is kept. A null option carries no information
     * and never regresses the status by itself.
     */
    static String mergeStatus(String currentStatus, String statusName, Boolean complete) {
        boolean completeProjection = STATUS_DONE.equals(currentStatus);
        boolean completeMatches = complete == null || complete == completeProjection;
        boolean statusMatches = statusName == null
            || statusName.equals(NotionTaskMapper.mapStatus(currentStatus));
        if (statusMatches && completeMatches) {
            return currentStatus;
        }
        return NotionTaskInboundMapper.resolveStatus(statusName, complete);
    }

    /** Applies a Notion type option; null or unknown options carry no information (never guessed). */
    static String mergeType(String currentType, String typeName) {
        if (typeName == null || typeName.equals(NotionTaskMapper.mapType(currentType))) {
            return currentType;
        }
        String mapped = NotionTaskInboundMapper.TYPE_FROM_NOTION.get(typeName);
        return mapped != null ? mapped : currentType;
    }

    /**
     * Applies a Notion text with the truncation-aware rule: a value equal to the 2000-char
     * projection of the current text is an echo of it and keeps the full original.
     */
    static String mergeText(String currentText, String incomingText) {
        String projection = NotionTaskMapper.projectText(currentText);
        if (Objects.equals(blankToNull(projection), blankToNull(incomingText))) {
            return currentText;
        }
        return blankToNull(incomingText);
    }

    /**
     * Applies a Notion date bound: a parsed value on the same instant as the current one
     * keeps the current representation; anything else (including null = cleared) applies.
     */
    static OffsetDateTime mergeInstant(OffsetDateTime current, String incomingRaw) {
        OffsetDateTime incoming = NotionTaskInboundMapper.parseNotionDate(incomingRaw);
        return sameInstant(incoming, current) ? current : incoming;
    }

    /**
     * Applies a Notion number: a null carries no information and keeps the current value.
     * Automation payloads may omit empty properties, and the scores are domain-computed
     * quantities mirrored into Notion — an absent number must never wipe them (the outbound
     * mirror re-asserts the domain value on the next propagation anyway).
     */
    static Double mergeNumber(Double current, Double incoming) {
        return incoming != null ? incoming : current;
    }

    /**
     * Applies a Notion scale select: null clears (full-mirror inbound), a known option maps
     * to its 1-based index, an unknown option carries no information and keeps the current
     * scale (never guessed).
     */
    static Integer mergeScale(Integer currentValue, String optionName, List<String> options) {
        if (optionName == null) {
            return null;
        }
        int index = options.indexOf(optionName);
        if (index < 0) {
            return currentValue;
        }
        return index + 1;
    }

    private static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.isEqual(b);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
