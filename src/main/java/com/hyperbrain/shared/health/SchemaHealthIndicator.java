package com.hyperbrain.shared.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates that the domain schema has been applied to the database, not just that a JDBC
 * connection is alive. Spring Boot's default db health indicator only calls isValid(), which
 * returns UP even when the schema is absent and the outbox is crash-looping.
 *
 * Uses to_regclass() — a single cheap lookup that returns NULL when the relation is missing.
 */
@Component
public class SchemaHealthIndicator extends AbstractHealthIndicator {

    private static final String SENTINEL_TABLE = "public.outbox_events";
    private final JdbcTemplate jdbcTemplate;

    public SchemaHealthIndicator(JdbcTemplate jdbcTemplate) {
        super("Schema health check failed");
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        String oid = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?)", String.class, SENTINEL_TABLE);
        if (oid != null) {
            builder.up().withDetail("schema", "domain schema present");
        } else {
            builder.down().withDetail("schema",
                    "domain schema missing — " + SENTINEL_TABLE + " not found; run apply-schema.sh");
        }
    }
}
