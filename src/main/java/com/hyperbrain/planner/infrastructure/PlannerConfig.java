package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.service.LearnedUnitCostCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free planner domain services as Spring beans, keeping the
 * {@code planner.domain} package free of framework annotations (spike #63).
 */
@Configuration
class PlannerConfig {

    @Bean
    LearnedUnitCostCalculator learnedUnitCostCalculator() {
        return new LearnedUnitCostCalculator();
    }
}
