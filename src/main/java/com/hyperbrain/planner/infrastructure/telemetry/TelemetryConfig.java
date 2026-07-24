package com.hyperbrain.planner.infrastructure.telemetry;

import com.hyperbrain.planner.domain.service.SleepScoreCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free telemetry domain services as Spring beans (ADR-016), keeping the
 * {@code planner.domain} package free of framework annotations. The {@link SleepScoreCalculator} is
 * built from the calibrable {@link SleepScoreProperties} ({@code app.telemetry.sleep-score.*}).
 */
@Configuration
@EnableConfigurationProperties(SleepScoreProperties.class)
class TelemetryConfig {

    @Bean
    SleepScoreCalculator sleepScoreCalculator(SleepScoreProperties properties) {
        return new SleepScoreCalculator(properties.toConfig());
    }
}
