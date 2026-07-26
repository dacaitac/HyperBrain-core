package com.hyperbrain.cognitive.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the cognitive module's {@link org.springframework.boot.context.properties.ConfigurationProperties}
 * beans (ADR-029 D5's committee intensity dials), keeping the properties record itself framework-light
 * and the wiring explicit — the same pattern as {@code TelemetryConfig}.
 */
@Configuration
@EnableConfigurationProperties(CommitteePromptProperties.class)
class CognitiveConfig {
}
