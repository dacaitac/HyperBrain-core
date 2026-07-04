package com.hyperbrain.shared.config;

import com.hyperbrain.shared.outbox.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the shared event-driven pipeline: enables scheduling (for the outbox relay) and
 * binds {@link OutboxProperties}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class PipelineConfig {
}
