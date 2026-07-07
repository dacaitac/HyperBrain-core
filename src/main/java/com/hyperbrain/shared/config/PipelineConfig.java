package com.hyperbrain.shared.config;

import com.hyperbrain.shared.outbox.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the shared event-driven pipeline: enables scheduling (for the outbox relay),
 * binds {@link OutboxProperties} and provides the virtual-thread executor the outbox drain
 * uses to run event propagators concurrently (HU-14 CA-13/CA-14).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class PipelineConfig {

    /**
     * Executor for concurrent outbox propagation. {@code SimpleAsyncTaskExecutor} with virtual
     * threads spawns one cheap thread per task — the right fit for the propagators' IO-bound
     * work (Notion REST, SQS publish), with no pool to size or exhaust (CA-14).
     */
    @Bean(name = "outboxPropagationExecutor")
    AsyncTaskExecutor outboxPropagationExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("outbox-propagation-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
