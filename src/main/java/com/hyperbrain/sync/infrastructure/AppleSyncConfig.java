package com.hyperbrain.sync.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link AppleCalendarProperties}. Unlike the Notion beans, the Apple write-back always
 * runs (there is no {@code enabled} gate), so this configuration is unconditional.
 */
@Configuration
@EnableConfigurationProperties(AppleCalendarProperties.class)
public class AppleSyncConfig {
}
