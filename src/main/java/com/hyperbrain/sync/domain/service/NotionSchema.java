package com.hyperbrain.sync.domain.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical names of the Notion Tasks / Cycles database properties the Core writes, plus the
 * read-only properties it must never touch (HU-10, CA-9). Mirrors the sanitized schema
 * documented in {@code 02-architecture/data-model.md} (ADR-011: the Notion schema converges
 * to the domain, not the other way around).
 */
public final class NotionSchema {

    private NotionSchema() {
    }

    // ── Tasks DB ──────────────────────────────────────────────────────────────
    public static final String PROP_NAME = "Name";
    public static final String PROP_DESCRIPTION = "Description";
    public static final String PROP_STATUS = "Status";
    public static final String PROP_COMPLETE = "Complete";
    public static final String PROP_TYPE = "Type";
    public static final String PROP_DATE = "Date";
    public static final String PROP_PRIORITY_SCORE = "Priority Score";
    public static final String PROP_URGENCE = "Urgence";
    public static final String PROP_EFFORT = "Effort";
    public static final String PROP_IMPACT = "Impact";
    public static final String PROP_ENERGY = "Energy";
    public static final String PROP_MENTAL_LOAD = "Mental Load";
    public static final String PROP_IMPORTANT = "Important";
    public static final String PROP_FREQUENCY = "Frequency";
    public static final String PROP_CYCLE = "Cycle";
    public static final String PROP_PARENT_TASK = "Parent Task";

    // ── Cycles DB ─────────────────────────────────────────────────────────────
    public static final String PROP_INACTIVE = "Inactive";

    // ── Canonical select options (1-based domain scale ↔ option index) ────────
    // Shared by the outbound (HU-10) and inbound (HU-14) mappers so both directions
    // agree on the same scale encoding.
    public static final List<String> IMPACT_OPTIONS =
        List.of("Irrelevante", "Bajo", "Moderado", "Alto", "Crítico");

    public static final List<String> ENERGY_OPTIONS =
        List.of("Automático", "Ejecución", "Sostenido", "Exigente", "Intenso");

    public static final List<String> MENTAL_LOAD_OPTIONS =
        List.of("Rutinario", "Foco", "Análisis", "Complejo", "Abstracto");

    /**
     * Formula and rollup properties (both databases): computed by Notion, writing them is a
     * programming error (CA-9).
     */
    public static final Set<String> READ_ONLY_PROPERTIES = Set.of(
        "Costo", "Theme Priority",          // Tasks
        "Presupuestado", "Ejecutado");      // Cycles

    /**
     * Asserts that a property map produced by a mapper contains no read-only property.
     *
     * @param properties the Notion property map about to be sent
     * @throws IllegalStateException if a formula/rollup property is present
     */
    public static void assertWritable(Map<String, Object> properties) {
        for (String key : properties.keySet()) {
            if (READ_ONLY_PROPERTIES.contains(key)) {
                throw new IllegalStateException(
                    "Attempt to write read-only Notion property '" + key + "' (CA-9)");
            }
        }
    }
}
