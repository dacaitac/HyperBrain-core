package com.hyperbrain.prioritizer.infrastructure;

import com.hyperbrain.prioritizer.domain.service.PriorityScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free prioritizer domain services as Spring beans, keeping the
 * {@code prioritizer.domain} package free of framework annotations (mirrors {@code PlannerConfig}).
 */
@Configuration
class PrioritizerConfig {

    @Bean
    PriorityScoreCalculator priorityScoreCalculator() {
        return new PriorityScoreCalculator();
    }
}
