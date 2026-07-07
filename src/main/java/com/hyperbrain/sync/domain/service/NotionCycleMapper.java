package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CycleSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

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
 *   <li>{@code type} ({@code MCI}/{@code ROUTINE}/{@code PHASE}) → {@code Type} (select)</li>
 *   <li>{@code start_date}/{@code end_date} → {@code Date} (date-only range)</li>
 *   <li>{@code status} → {@code Inactive} (checkbox, inverted: true iff COMPLETED)</li>
 * </ul>
 *
 * <p>{@code core_cycle} has no description column in data-model v2.x, so the Notion
 * {@code Description} property is left untouched. Read-only formulas ({@code Presupuestado},
 * {@code Ejecutado}) are never produced (CA-9). Thread-safe: stateless, static methods only.
 */
public final class NotionCycleMapper {

    private static final Map<String, String> TYPE_TO_NOTION = Map.of(
        "MCI", "MCI",
        "ROUTINE", "Routine",
        "PHASE", "Phase");

    private NotionCycleMapper() {
    }

    /**
     * Builds the Notion Cycles property map for one cycle.
     *
     * @param snapshot the cycle state to propagate
     * @return an insertion-ordered property map, guaranteed free of read-only properties
     */
    public static Map<String, Object> map(CycleSnapshot snapshot) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(NotionSchema.PROP_NAME, NotionTaskMapper.title(snapshot.name()));
        String notionType = snapshot.type() != null ? TYPE_TO_NOTION.get(snapshot.type()) : null;
        if (notionType != null) {
            props.put(NotionSchema.PROP_TYPE, NotionTaskMapper.select(notionType));
        }
        if (snapshot.startDate() != null) {
            props.put(NotionSchema.PROP_DATE, NotionTaskMapper.dateValue(
                snapshot.startDate().toString(),
                snapshot.endDate() != null ? snapshot.endDate().toString() : null));
        }
        props.put(NotionSchema.PROP_INACTIVE,
            NotionTaskMapper.checkbox("COMPLETED".equals(snapshot.status())));
        NotionSchema.assertWritable(props);
        return props;
    }
}
