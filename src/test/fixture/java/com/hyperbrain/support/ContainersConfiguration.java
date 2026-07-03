package com.hyperbrain.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Shared Testcontainers declarations for integration tests.
 * Imported via {@code @ImportTestcontainers(ContainersConfiguration.class)} in
 * {@link IntegrationTest}. Spring Boot manages container lifecycle.
 *
 * <p>LocalStack (SQS) will be added here when the first SQS integration test requires it.
 */
public interface ContainersConfiguration {

    @Container
    @ServiceConnection
    PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
}
