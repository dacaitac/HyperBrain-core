package com.hyperbrain.sync.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link NotionSyncProperties}. The Notion beans themselves are gated by
 * {@code app.sync.notion.enabled} so a deployment without a token runs Apple-only.
 */
@Configuration
@EnableConfigurationProperties(NotionSyncProperties.class)
public class NotionSyncConfig {
}
