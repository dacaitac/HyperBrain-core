package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.sync.domain.port.out.PlannerBlockDeletionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * JDBC adapter that lets the inbound Apple delete path remove a planner block (#13) while keeping the
 * ownership of {@code core_time_block} semantics inside {@code planner}. It implements the
 * {@code sync}-owned {@link PlannerBlockDeletionPort}, so the compile dependency runs
 * {@code planner → sync} (the same direction as the write-back {@code AgendaBlockPropagator}), never
 * the reverse.
 *
 * <p>The delete is scoped exactly like {@link JdbcPlannerStateRepository}'s daily regeneration clear
 * ({@code origin = 'PLANNER'} and {@code status = 'PLANNED'}) so a user removing the calendar event of
 * a still-planned block drops that block, while {@code FOCUS}/{@code USER} blocks and any
 * {@code ACTIVE}/{@code SETTLED} work (which holds telemetry) survive. The {@code ON DELETE CASCADE}
 * on {@code core_time_block.executable_id} points at the block, not from it, so the scheduled
 * executable is never removed.
 */
@Repository
class PlannerBlockDeletionAdapter implements PlannerBlockDeletionPort {

    private static final String DELETE_PLANNED_BLOCK_SQL = """
        DELETE FROM core_time_block
        WHERE id = ?
          AND origin = 'PLANNER'
          AND status = 'PLANNED'
        """;

    private final JdbcTemplate jdbcTemplate;

    PlannerBlockDeletionAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean deletePlannedBlock(UUID blockId) {
        return jdbcTemplate.update(DELETE_PLANNED_BLOCK_SQL, blockId) > 0;
    }
}
