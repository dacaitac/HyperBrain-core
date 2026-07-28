package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.sync.domain.port.out.ScheduledDueTimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter resolving the hour the Planner scheduled an executable (core#50, Part C), so the Apple
 * write-back can project it onto the executable's EKReminder due date. It implements the {@code sync}-owned
 * {@link ScheduledDueTimeProvider}, keeping the compile dependency {@code planner → sync} (the same
 * direction as {@code AgendaBlockPropagator} and {@code PlannerBlockDeletionAdapter}), never the reverse,
 * so ArchUnit stays green and the ownership of {@code core_time_block} semantics stays in {@code planner}.
 *
 * <p>The scheduled start is the earliest {@code PLANNED}/{@code PLANNER} block start where the executable
 * appears — as the block's anchor ({@code core_time_block.executable_id}) or as a themed companion
 * ({@code core_time_block_member}, ADR-027 D1). Only regenerable planner blocks count: a {@code USER}/
 * {@code FOCUS} block or already {@code ACTIVE}/{@code SETTLED} work is not the Planner's projection to
 * assert onto a reminder.
 */
@Repository
class JdbcScheduledDueTimeProvider implements ScheduledDueTimeProvider {

    private static final String SCHEDULED_START_SQL = """
        SELECT MIN(b.date_start)
        FROM core_time_block b
        LEFT JOIN core_time_block_member m ON m.block_id = b.id
        WHERE b.status = 'PLANNED'
          AND b.origin = 'PLANNER'
          AND (b.executable_id = ? OR m.executable_id = ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcScheduledDueTimeProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<OffsetDateTime> scheduledStart(UUID executableId) {
        OffsetDateTime start = jdbcTemplate.queryForObject(
            SCHEDULED_START_SQL, OffsetDateTime.class, executableId, executableId);
        return Optional.ofNullable(start);
    }
}
