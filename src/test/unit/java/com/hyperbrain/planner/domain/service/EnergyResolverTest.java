package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.EnergyTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("EnergyResolver (#6a, sleep_score -> F3 margin / F6 quota)")
class EnergyResolverTest {

    private final EnergyResolver resolver = new EnergyResolver(EnergyThresholds.DEFAULT);

    @Test
    @DisplayName("no fresh score: NEUTRAL default (margin 25%, quota 3) with a readable criterion")
    void no_score_defaults_to_neutral() {
        EnergyProfile profile = resolver.resolve(null);

        assertThat(profile.tier()).isEqualTo(EnergyTier.NEUTRAL);
        assertThat(profile.chaosMarginFraction()).isCloseTo(0.25, within(1e-9));
        assertThat(profile.highLoadQuota()).isEqualTo(3);
        assertThat(profile.criterion()).contains("neutral").contains("25%").contains("quota 3");
    }

    @Test
    @DisplayName("F2: low score widens the margin to 35% (decoupled) and tightens the quota to 2")
    void low_score_widens_margin() {
        EnergyProfile profile = resolver.resolve(45);

        assertThat(profile.tier()).isEqualTo(EnergyTier.LOW);
        assertThat(profile.chaosMarginFraction()).isCloseTo(0.35, within(1e-9));
        assertThat(profile.highLoadQuota()).isEqualTo(2);
        assertThat(profile.criterion()).contains("Sleep Score 45").contains("35%").contains("2");
    }

    @Test
    @DisplayName("F2: high score keeps the margin at 25% (decoupled, not 20%) and quota at 3")
    void high_score_keeps_margin_decoupled() {
        EnergyProfile profile = resolver.resolve(90);

        assertThat(profile.tier()).isEqualTo(EnergyTier.HIGH);
        assertThat(profile.chaosMarginFraction()).isCloseTo(0.25, within(1e-9));
        assertThat(profile.highLoadQuota()).isEqualTo(3);
    }

    @Test
    @DisplayName("score between the cuts: NEUTRAL band")
    void mid_score_is_neutral() {
        EnergyProfile profile = resolver.resolve(70);

        assertThat(profile.tier()).isEqualTo(EnergyTier.NEUTRAL);
        assertThat(profile.highLoadQuota()).isEqualTo(3);
    }

    @Test
    @DisplayName("boundary: lowCeiling is exclusive (NEUTRAL), highFloor is inclusive (HIGH)")
    void boundaries_are_honoured() {
        assertThat(resolver.resolve(60).tier()).isEqualTo(EnergyTier.NEUTRAL);
        assertThat(resolver.resolve(59).tier()).isEqualTo(EnergyTier.LOW);
        assertThat(resolver.resolve(80).tier()).isEqualTo(EnergyTier.HIGH);
        assertThat(resolver.resolve(79).tier()).isEqualTo(EnergyTier.NEUTRAL);
    }
}
