package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.NotionCyclePage;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/*
 * Design pattern: Translator (a.k.a. Data Mapper)
 * Reason: same rationale as NotionTaskInboundMapper — exact inverse of NotionCycleMapper
 * (HU-10) in one pure class (HU-14 CA-5).
 */

/**
 * Maps a {@link NotionCyclePage} to a {@link CycleSnapshot} — the inverse of
 * {@link NotionCycleMapper} (HU-14, ADR-011: Cycles sync is fully bidirectional):
 * <ul>
 *   <li>{@code Type} select → cycle type; unknown options degrade to {@code PHASE} (the
 *       least constrained type) instead of failing.
 *   <li>{@code Inactive} checkbox → status: checked → {@code COMPLETED}, else {@code ACTIVE}.
 *   <li>{@code Date} range → {@code start_date}/{@code end_date} (date part only).
 * </ul>
 *
 * <p><b>AREA perpetuity (ADR-036).</b> An {@code AREA} is a perpetual life-area classification: it
 * never carries an {@code end_date} and is always {@code ACTIVE}. Whatever a person set on the Notion
 * page, an {@code AREA} snapshot is coerced to {@code end_date = null} / {@code status = ACTIVE} before
 * persistence, honouring the {@code core_cycle_area_perpetual} DB CHECK (belt-and-suspenders).
 *
 * <p>Thread-safe: stateless, static methods only.
 */
public final class NotionCycleInboundMapper {

    private static final String AREA = "AREA";

    // ADR-015: horizon ladder — CORE_CYCLE absorbs the former CORE_PROJECT (type PROJECT).
    // ADR-036: AREA classifies life areas.
    private static final Map<String, String> TYPE_FROM_NOTION = Map.of(
        "MCI", "MCI",
        "Goal", "GOAL",
        "Objective", "OBJECTIVE",
        "Project", "PROJECT",
        "Phase", "PHASE",
        "Routine", "ROUTINE",
        "Area", AREA);

    private NotionCycleInboundMapper() {
    }

    /**
     * Builds the cycle snapshot for one Notion Cycles page. The parent relation arrives already
     * resolved to a local id by the caller ({@code NotionCycleSyncService}), mirroring how
     * {@code NotionTaskInboundMapper} receives {@code parentId}.
     *
     * @param page                  the parsed page properties
     * @param id                    local {@code core_cycle} id (existing mapping or a fresh UUID)
     * @param userId                owning user (single-user MVP)
     * @param resolvedParentCycleId local parent cycle id resolved by the caller, or null (no
     *                              relation, unmapped parent, or self-parent discarded)
     * @return the snapshot to persist
     */
    public static CycleSnapshot toSnapshot(NotionCyclePage page, UUID id, UUID userId,
                                           UUID resolvedParentCycleId) {
        String type = mapType(page.typeName());
        boolean isArea = AREA.equals(type);
        // ADR-036: an AREA is perpetual — coerce away any end_date / COMPLETED a person may have set.
        String status = isArea || !Boolean.TRUE.equals(page.inactive()) ? "ACTIVE" : "COMPLETED";
        LocalDate endDate = isArea ? null : parseDate(page.dateEnd());
        return new CycleSnapshot(
            id,
            userId,
            resolvedParentCycleId,
            page.name() != null ? page.name() : "",
            type,
            status,
            parseDate(page.dateStart()),
            endDate);
    }

    static String mapType(String typeName) {
        String mapped = typeName != null ? TYPE_FROM_NOTION.get(typeName) : null;
        return mapped != null ? mapped : "PHASE";
    }

    /** Parses the date part of a Notion date value; unparseable values map to null. */
    static LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
