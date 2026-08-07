package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.SlotPurpose;
import com.hyperbrain.planner.domain.model.TemplateSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the planner's calibrable constants from {@code sys_user.settings.planner_constants} (JSONB, no
 * column migration) and builds the domain {@link PlannerConstraints} / {@link EnergyThresholds}
 * calibration seams, following the {@code AlignmentWeights} pattern — the sanctioned defaults stand in
 * for every absent key, so an empty or partial {@code planner_constants} object degrades to
 * {@code DEFAULT} without failing. No formula constant is hard-coded inside a domain service.
 *
 * <p>Single-user MVP: the constants are resolved once from the seeded {@code SYS_USER} at wiring time
 * (mirroring how the Prioritizer uses a single {@code AlignmentWeights} instance). A future per-user
 * calibration would move this read onto the request path; that is out of this scope.
 *
 * <p>Recognised keys under {@code planner_constants} (all optional): {@code minSleepSamples},
 * {@code sleepHistoryDays}, {@code sleepFreshnessHours}, {@code wigBlockMinutes},
 * {@code pacePrecisionEpsilon}, {@code maxRequiredPace}, {@code hysteresisMargin},
 * {@code degradedStreakThreshold}, {@code degradedUrgentCount}, {@code highLoadDrainFloor},
 * {@code lowCeiling}, {@code highFloor}, {@code lowQuota}, {@code neutralQuota}, {@code highQuota},
 * and the {@code day_template} object (ADR-040 D14). Keys of retired mechanisms that linger in a
 * deployed settings blob are simply ignored, which is what makes their removal deploy-safe.
 */
@Component
class PlannerConstantsLoader {

    private static final Logger log = LoggerFactory.getLogger(PlannerConstantsLoader.class);

    private static final String PLANNER_CONSTANTS_SQL = """
        SELECT settings #> '{planner_constants}' AS planner_constants
        FROM sys_user
        WHERE role = 'ADMIN'
        ORDER BY created_at
        LIMIT 1
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    PlannerConstantsLoader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the planner constraints from settings, defaulting every absent key.
     *
     * @return the resolved {@link PlannerConstraints}; {@code DEFAULT} when settings carry none
     */
    PlannerConstraints resolveConstraints() {
        JsonNode node = readNode();
        if (node == null) {
            return PlannerConstraints.DEFAULT;
        }
        PlannerConstraints d = PlannerConstraints.DEFAULT;
        try {
            return new PlannerConstraints(
                intOr(node, "minSleepSamples", d.minSleepSamples()),
                intOr(node, "sleepHistoryDays", d.sleepHistoryDays()),
                intOr(node, "sleepFreshnessHours", d.sleepFreshnessHours()),
                intOr(node, "wigBlockMinutes", d.wigBlockMinutes()),
                doubleOr(node, "pacePrecisionEpsilon", d.pacePrecisionEpsilon()),
                doubleOr(node, "maxRequiredPace", d.maxRequiredPace()),
                doubleOr(node, "hysteresisMargin", d.hysteresisMargin()),
                intOr(node, "degradedStreakThreshold", d.degradedStreakThreshold()),
                intOr(node, "degradedUrgentCount", d.degradedUrgentCount()),
                intOr(node, "highLoadDrainFloor", d.highLoadDrainFloor()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid planner_constants override; falling back to defaults: {}", e.getMessage());
            return PlannerConstraints.DEFAULT;
        }
    }

    /**
     * Resolves the energy thresholds from settings, defaulting every absent key.
     *
     * @return the resolved {@link EnergyThresholds}; {@code DEFAULT} when settings carry none
     */
    EnergyThresholds resolveThresholds() {
        JsonNode node = readNode();
        if (node == null) {
            return EnergyThresholds.DEFAULT;
        }
        EnergyThresholds d = EnergyThresholds.DEFAULT;
        try {
            return new EnergyThresholds(
                intOr(node, "lowCeiling", d.lowCeiling()),
                intOr(node, "highFloor", d.highFloor()),
                intOr(node, "lowQuota", d.lowQuota()),
                intOr(node, "neutralQuota", d.neutralQuota()),
                intOr(node, "highQuota", d.highQuota()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid energy thresholds override; falling back to defaults: {}", e.getMessage());
            return EnergyThresholds.DEFAULT;
        }
    }

    /**
     * Resolves the day template from settings (ADR-040 D14 — the template lives in the user's settings,
     * with no schema of its own). The expected shape under {@code planner_constants.day_template} is
     * <pre>{@code
     * {"wake_anchor": "07:00",
     *  "slots": [{"id": "GOAL_MORNING", "label": "Meta de la mañana",
     *             "start": "08:30", "end": "10:30", "purpose": "GOAL"}, ...]}
     * }</pre>
     * A missing, malformed or empty object degrades to the sanctioned {@link DayTemplate#DEFAULT} —
     * never to an absent template, because a floor with no reference structure collapses the whole day
     * against the first hour (ADR-040 D2).
     *
     * <p><b>{@code label} is optional and degrades in two steps</b>, so a settings blob written before
     * labels existed still yields a readable day: the sanctioned label of a slot with the same id, and
     * only failing that the id itself. Falling straight back to the id would re-open the defect this
     * exists to close — a calendar full of {@code MEETING_ZONE}.
     *
     * @return the resolved template; {@code DEFAULT} when settings carry none or carry an invalid one
     */
    DayTemplate resolveDayTemplate() {
        JsonNode root = readNode();
        JsonNode node = root == null ? null : root.get("day_template");
        if (node == null || !node.isObject()) {
            return DayTemplate.DEFAULT;
        }
        try {
            return parseDayTemplate(node);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            log.warn("Invalid day_template override; falling back to the sanctioned template: {}",
                e.getMessage());
            return DayTemplate.DEFAULT;
        }
    }

    private static DayTemplate parseDayTemplate(JsonNode node) {
        JsonNode slotsNode = node.get("slots");
        if (slotsNode == null || !slotsNode.isArray() || slotsNode.isEmpty()) {
            throw new IllegalArgumentException("day_template.slots must be a non-empty array");
        }
        List<TemplateSlot> slots = new ArrayList<>(slotsNode.size());
        for (JsonNode slot : slotsNode) {
            String id = text(slot, "id");
            slots.add(new TemplateSlot(
                id,
                label(slot, id),
                minuteOfDay(text(slot, "start")),
                minuteOfDay(text(slot, "end")),
                SlotPurpose.valueOf(text(slot, "purpose"))));
        }
        JsonNode anchor = node.get("wake_anchor");
        int wakeAnchor = anchor != null && anchor.isTextual()
            ? minuteOfDay(anchor.asText())
            : DayTemplate.DEFAULT.wakeAnchorMinute();
        return new DayTemplate(wakeAnchor, slots);
    }

    /**
     * The slot's readable label: the configured one, else the sanctioned label of the slot with the
     * same id, else the id — the last resort, and the only shape in which a technical key can still
     * reach a calendar event.
     */
    private static String label(JsonNode slot, String id) {
        JsonNode value = slot.get("label");
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            return value.asText();
        }
        return DayTemplate.DEFAULT.labelOf(id).orElse(id);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("day_template slot is missing '" + field + "'");
        }
        return value.asText();
    }

    private static int minuteOfDay(String hhmm) {
        LocalTime time = LocalTime.parse(hhmm.strip());
        return time.getHour() * 60 + time.getMinute();
    }

    private JsonNode readNode() {
        String json = jdbcTemplate.query(PLANNER_CONSTANTS_SQL,
            rs -> rs.next() ? rs.getString("planner_constants") : null);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Unparseable planner_constants JSON; using defaults: {}", e.getMessage());
            return null;
        }
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    private static double doubleOr(JsonNode node, String field, double fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }
}
