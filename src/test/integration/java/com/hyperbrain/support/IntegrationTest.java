package com.hyperbrain.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

/**
 * Meta-annotation for integration tests: starts a real PostgreSQL container
 * (postgres:16-alpine) via {@link ContainersConfiguration} and activates the
 * {@code integration-test} profile for fake AWS credentials.
 *
 * <p>LocalStack (SQS) will be added to {@link ContainersConfiguration} once
 * the first SQS integration test is implemented.
 *
 * <p>Usage:
 * <pre>{@code
 * @IntegrationTest
 * class MyIT {
 *     @Autowired SomeService svc;
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ImportTestcontainers(ContainersConfiguration.class)
@ActiveProfiles("integration-test")
public @interface IntegrationTest {
}
