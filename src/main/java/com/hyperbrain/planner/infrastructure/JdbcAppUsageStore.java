package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.AppUsageBucket;
import com.hyperbrain.planner.domain.port.out.AppUsageStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link AppUsageStore}: writes one {@code tel_app_usage} row per bucket in a single
 * batch. Envelope-level {@code dedup_key} idempotency upstream means this performs no dedup of its own.
 */
@Repository
class JdbcAppUsageStore implements AppUsageStore {

    private static final String INSERT_SQL = """
        INSERT INTO tel_app_usage
            (id, user_id, bucket_start, bucket_end, category, duration_seconds, pickups, context_event_id, collected_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcAppUsageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveBuckets(UUID userId, List<AppUsageBucket> buckets, UUID contextEventId,
                            OffsetDateTime collectedAt) {
        if (buckets.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, buckets, buckets.size(), (ps, bucket) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, bucket.bucketStart());
            ps.setObject(4, bucket.bucketEnd());
            ps.setString(5, bucket.category());
            ps.setInt(6, bucket.durationSeconds());
            if (bucket.pickups() == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, bucket.pickups());
            }
            ps.setObject(8, contextEventId);
            ps.setObject(9, collectedAt);
        });
    }
}
