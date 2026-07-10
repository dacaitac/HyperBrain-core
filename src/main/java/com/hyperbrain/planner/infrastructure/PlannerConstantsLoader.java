package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * {@code lowCeiling}, {@code highFloor}, {@code lowMargin}, {@code neutralMargin}, {@code highMargin},
 * {@code lowQuota}, {@code neutralQuota}, {@code highQuota}.
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
                doubleOr(node, "lowMargin", d.lowMargin()),
                doubleOr(node, "neutralMargin", d.neutralMargin()),
                doubleOr(node, "highMargin", d.highMargin()),
                intOr(node, "lowQuota", d.lowQuota()),
                intOr(node, "neutralQuota", d.neutralQuota()),
                intOr(node, "highQuota", d.highQuota()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid energy thresholds override; falling back to defaults: {}", e.getMessage());
            return EnergyThresholds.DEFAULT;
        }
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
