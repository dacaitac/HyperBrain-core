package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CycleSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.singletonMap;

/*
 * Design pattern: Translator (a.k.a. Data Mapper)
 * Reason: same rationale as NotionTaskMapper — one pure class owns the core_cycle → Notion
 * Cycles representation so service and tests share it.
 */

/**
 * Maps a {@link CycleSnapshot} to the Notion Cycles property map (HU-10, ADR-011).
 *
 * <p>Mapping contract (issue #15):
 * <ul>
 *   <li>{@code name} → {@code Name} (title)</li>
 *   <li>{@code type} ({@code MCI}/{@code GOAL}/{@code OBJECTIVE}/{@code PROJECT}/{@code PHASE}/{@code ROUTINE}) → {@code Type} (select)</li>
 *   <li>{@code start_date}/{@code end_date} → {@code Date} (date-only range)</li>
 *   <li>{@code status} → {@code Inactive} (checkbox, inverted: true iff COMPLETED)</li>
 *   <li>{@code parent_cycle_id} → {@code Cycle Parent (Objective)} (self-relation; unresolved
 *       or absent → empty relation list, ADR-015 horizon ladder)</li>
 *   <li>{@code core_cycle_area} memberships → {@code Areas} (self-relation; the life AREAs this
 *       cycle serves, ADR-036). Empty → empty relation list.</li>
 * </ul>
 *
 * <p>Full-mirror contract (ADR-012 D3): null {@code type}/{@code start_date} clear their
 * properties explicitly. {@code core_cycle} has no description column in data-model v2.x, so
 * the Notion {@code Description} property is left untouched. Read-only formulas
 * ({@code Presupuestado}, {@code Ejecutado}) are never produced (CA-9). Thread-safe:
 * stateless, static methods only.
 */
public final class NotionCycleMapper {

    // ADR-015: horizon ladder — CORE_CYCLE absorbs the former CORE_PROJECT (type PROJECT).
    // ADR-036: AREA classifies life areas.
    private static final Map<String, String> TYPE_TO_NOTION = Map.of(
        "MCI", "MCI",
        "GOAL", "Goal",
        "OBJECTIVE", "Objective",
        "PROJECT", "Project",
        "PHASE", "Phase",
        "ROUTINE", "Routine",
        "AREA", "Area");

    private NotionCycleMapper() {
    }

    /**
     * Builds the Notion Cycles property map for one cycle. The parent's Notion page id is
     * supplied by the caller (the propagator resolves it from {@code sync_mappings}), mirroring
     * how {@link NotionTaskMapper#map} receives the cycle/parent external ids.
     *
     * @param snapshot         the cycle state to propagate
     * @param parentExternalId Notion page id of the parent cycle, or null when unmapped/absent
     * @param areaExternalIds  Notion page ids of the AREAs this cycle serves (core_cycle_area,
     *                         ADR-036); never null, empty clears the relation. Sorted internally so
     *                         the produced relation — and therefore the sync checksum — is
     *                         order-independent of the caller (loop protection, RF-17).
     * @return an insertion-ordered property map, guaranteed free of read-only properties
     */
    public static Map<String, Object> map(CycleSnapshot snapshot, String parentExternalId,
                                           List<String> areaExternalIds) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(NotionSchema.PROP_NAME, NotionTaskMapper.title(snapshot.name()));
        String notionType = snapshot.type() != null ? TYPE_TO_NOTION.get(snapshot.type()) : null;
        props.put(NotionSchema.PROP_TYPE, notionType != null
            ? NotionTaskMapper.select(notionType)
            : singletonMap("select", null));
        props.put(NotionSchema.PROP_DATE, snapshot.startDate() != null
            ? NotionTaskMapper.dateValue(
                snapshot.startDate().toString(),
                snapshot.endDate() != null ? snapshot.endDate().toString() : null)
            : singletonMap("date", null));
        props.put(NotionSchema.PROP_INACTIVE,
            NotionTaskMapper.checkbox("COMPLETED".equals(snapshot.status())));
        props.put(NotionSchema.PROP_PARENT_CYCLE, parentExternalId != null
            ? NotionTaskMapper.relation(parentExternalId)
            : Map.of("relation", List.of()));
        props.put(NotionSchema.PROP_AREAS, areasRelation(areaExternalIds));
        NotionSchema.assertWritable(props);
        return props;
    }

    /**
     * Builds the {@code Areas} self-relation value (ADR-036), sorted for a deterministic, caller-order
     * -independent representation so the inbound and outbound sync checksums agree (RF-17).
     *
     * @param areaExternalIds the AREA page ids; never null
     * @return the Notion relation property value (empty list clears the relation)
     */
    private static Object areasRelation(List<String> areaExternalIds) {
        List<Object> relations = areaExternalIds.stream()
            .sorted()
            .map(id -> Map.<String, Object>of("id", id))
            .collect(Collectors.toList());
        return Map.of("relation", relations);
    }
}
