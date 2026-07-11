package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.service.AgendaGenerator;
import com.hyperbrain.planner.domain.service.AgendaValidator;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.LearnedUnitCostCalculator;
import com.hyperbrain.planner.domain.service.MorningTriggerCalculator;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free planner domain services as Spring beans, keeping the
 * {@code planner.domain} package free of framework annotations (spike #63, #6a). The calibrable
 * {@link PlannerConstraints} and {@link EnergyThresholds} are resolved from
 * {@code sys_user.settings.planner_constants} through {@link PlannerConstantsLoader} (F3 — no formula
 * constant hard-coded in a service), falling back to the sanctioned defaults when settings carry none.
 */
@Configuration
@EnableConfigurationProperties(AgendaDeliveryProperties.class)
class PlannerConfig {

    @Bean
    PlannerConstraints plannerConstraints(PlannerConstantsLoader loader) {
        return loader.resolveConstraints();
    }

    @Bean
    EnergyThresholds energyThresholds(PlannerConstantsLoader loader) {
        return loader.resolveThresholds();
    }

    @Bean
    LearnedUnitCostCalculator learnedUnitCostCalculator() {
        return new LearnedUnitCostCalculator();
    }

    @Bean
    SleepFrontierCalculator sleepFrontierCalculator() {
        return new SleepFrontierCalculator();
    }

    @Bean
    EnergyResolver energyResolver(EnergyThresholds energyThresholds) {
        return new EnergyResolver(energyThresholds);
    }

    @Bean
    PlanningWindowResolver planningWindowResolver() {
        return new PlanningWindowResolver();
    }

    @Bean
    AgendaGenerator agendaGenerator(PlannerConstraints plannerConstraints) {
        return new AgendaGenerator(plannerConstraints);
    }

    @Bean
    AgendaValidator agendaValidator() {
        return new AgendaValidator();
    }

    @Bean
    MorningTriggerCalculator morningTriggerCalculator(AgendaDeliveryProperties properties) {
        return new MorningTriggerCalculator(
            properties.leadOffsetMinutes(), properties.hysteresisMarginMinutes());
    }
}
