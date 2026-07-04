package com.hyperbrain.shared.messaging.infrastructure;

import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter for {@link ProcessedMessageStore}. The {@code ON CONFLICT DO NOTHING} clause makes
 * the insert idempotent; the affected-row count tells the caller whether the message is new.
 */
@Repository
public class JdbcProcessedMessageStore implements ProcessedMessageStore {

    private static final String INSERT_SQL =
        "INSERT INTO processed_message (message_id, event_type) VALUES (?, ?) ON CONFLICT (message_id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessedMessageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean markProcessed(String messageId, String eventType) {
        return jdbcTemplate.update(INSERT_SQL, messageId, eventType) == 1;
    }
}
