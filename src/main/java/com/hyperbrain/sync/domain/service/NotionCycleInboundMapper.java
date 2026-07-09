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
 * <p>Thread-safe: stateless, static methods only.
 */
public final class NotionCycleInboundMapper {

    // ADR-015: horizon ladder — CORE_CYCLE absorbs the former CORE_PROJECT (type PROJECT).
    private static final Map<String, String> TYPE_FROM_NOTION = Map.of(
        "MCI", "MCI",
        "Goal", "GOAL",
        "Objective", "OBJECTIVE",
        "Project", "PROJECT",
        "Phase", "PHASE",
        "Routine", "ROUTINE");

    private NotionCycleInboundMapper() {
    }

    /**
     * Builds the cycle snapshot for one Notion Cycles page.
     *
     * @param page   the parsed page properties
     * @param id     local {@code core_cycle} id (existing mapping or a fresh UUID)
     * @param userId owning user (single-user MVP)
     * @return the snapshot to persist
     */
    public static CycleSnapshot toSnapshot(NotionCyclePage page, UUID id, UUID userId) {
        return new CycleSnapshot(
            id,
            userId,
            page.name() != null ? page.name() : "",
            mapType(page.typeName()),
            Boolean.TRUE.equals(page.inactive()) ? "COMPLETED" : "ACTIVE",
            parseDate(page.dateStart()),
            parseDate(page.dateEnd()));
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
