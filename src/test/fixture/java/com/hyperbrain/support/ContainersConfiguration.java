package com.hyperbrain.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared Testcontainers declarations for integration tests. Imported via
 * {@code @ImportTestcontainers(ContainersConfiguration.class)} in {@link IntegrationTest};
 * Spring Boot manages container lifecycle and wires both via {@code @ServiceConnection}
 * (PostgreSQL datasource, LocalStack SQS endpoint).
 *
 * <p>The containers are static, so a single PostgreSQL and a single LocalStack are reused across
 * every integration test class in the run. LocalStack creates the SQS queues from an init hook and
 * only reports ready once they exist, so listeners never race a missing queue at startup.
 */
public interface ContainersConfiguration {

    @Container
    @ServiceConnection
    PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    @ServiceConnection
    LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4"))
        .withEnv("SERVICES", "sqs")
        .withCopyToContainer(
            MountableFile.forClasspathResource("localstack/init-queues.sh", 0777),
            "/etc/localstack/init/ready.d/init-queues.sh")
        .waitingFor(Wait.forLogMessage(".*HyperBrain queues ready.*", 1));
}
