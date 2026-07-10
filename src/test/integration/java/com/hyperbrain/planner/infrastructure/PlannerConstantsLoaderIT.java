package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the F3 externalization of the planner's formula constants against a real PostgreSQL: the
 * {@link PlannerConstantsLoader} reads {@code sys_user.settings.planner_constants} (JSONB, no column
 * migration) and overrides only the keys present, defaulting the rest — so a partial object never
 * fails and never silently drops a default.
 */
@IntegrationTest
@DisplayName("PlannerConstantsLoader — F3 settings-backed constants (no schema change)")
class PlannerConstantsLoaderIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlannerConstantsLoader loader;

    @AfterEach
    void resetSettings() {
        jdbcTemplate.update("UPDATE sys_user SET settings = '{}'::jsonb WHERE id = ?", USER);
    }

    @Test
    @DisplayName("empty settings: every constant falls back to the sanctioned default")
    void empty_settings_defaults_everything() {
        jdbcTemplate.update("UPDATE sys_user SET settings = '{}'::jsonb WHERE id = ?", USER);

        assertThat(loader.resolveConstraints())
            .usingRecursiveComparison().isEqualTo(PlannerConstraints.DEFAULT);
        assertThat(loader.resolveThresholds())
            .usingRecursiveComparison().isEqualTo(EnergyThresholds.DEFAULT);
    }

    @Test
    @DisplayName("partial override: named keys win, absent keys keep the default")
    void partial_override_keeps_absent_defaults() {
        jdbcTemplate.update("""
            UPDATE sys_user
            SET settings = '{"planner_constants": {
                "wigBlockMinutes": 60,
                "pacePrecisionEpsilon": 0.1,
                "hysteresisMargin": 0.2,
                "degradedStreakThreshold": 4,
                "highMargin": 0.3,
                "highQuota": 2
            }}'::jsonb
            WHERE id = ?
            """, USER);

        PlannerConstraints constraints = loader.resolveConstraints();
        assertThat(constraints.wigBlockMinutes()).isEqualTo(60);
        assertThat(constraints.pacePrecisionEpsilon()).isEqualTo(0.1);
        assertThat(constraints.hysteresisMargin()).isEqualTo(0.2);
        assertThat(constraints.degradedStreakThreshold()).isEqualTo(4);
        // Untouched keys keep the default.
        assertThat(constraints.maxRequiredPace()).isEqualTo(PlannerConstraints.DEFAULT.maxRequiredPace());
        assertThat(constraints.highLoadDrainFloor())
            .isEqualTo(PlannerConstraints.DEFAULT.highLoadDrainFloor());

        EnergyThresholds thresholds = loader.resolveThresholds();
        assertThat(thresholds.highMargin()).isEqualTo(0.3);
        assertThat(thresholds.highQuota()).isEqualTo(2);
        assertThat(thresholds.lowMargin()).isEqualTo(EnergyThresholds.DEFAULT.lowMargin());
    }

    @Test
    @DisplayName("invalid override (below the WIG minimum): falls back to defaults, never throws")
    void invalid_override_falls_back() {
        jdbcTemplate.update(
            "UPDATE sys_user SET settings = '{\"planner_constants\": {\"wigBlockMinutes\": 10}}'::jsonb "
                + "WHERE id = ?", USER);

        assertThat(loader.resolveConstraints())
            .usingRecursiveComparison().isEqualTo(PlannerConstraints.DEFAULT);
    }
}
