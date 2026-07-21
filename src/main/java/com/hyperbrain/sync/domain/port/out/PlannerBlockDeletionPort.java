package com.hyperbrain.sync.domain.port.out;

import java.util.UUID;

/**
 * Outbound port through which the inbound Apple calendar-event delete path (#13) removes the
 * {@code PLANNER}-origin {@code core_time_block} that a deleted EKEvent materialized, without the
 * {@code sync} module reaching into {@code planner} directly.
 *
 * <p><b>Cross-module seam (for Daniel's review).</b> A morning-agenda block is written to Apple as a
 * calendar event by {@code AgendaBlockPropagator} and mapped in {@code sync_mappings} under its own
 * {@code core_time_block.id}. When Daniel deletes that event in iOS, the local block must go too, but
 * ArchUnit keeps {@code sync} from importing {@code planner}. This port is the hexagonal answer:
 * {@code sync} declares the capability it needs and {@code planner.infrastructure} provides the JDBC
 * adapter, so the {@code planner → sync} dependency direction (already present for the write-back) is
 * preserved and the ownership of {@code core_time_block} semantics stays in {@code planner}.
 */
public interface PlannerBlockDeletionPort {

    /**
     * Deletes the {@code PLANNED}/{@code PLANNER} {@code core_time_block} identified by {@code blockId}.
     *
     * <p>Scoped to {@code origin = 'PLANNER'} and {@code status = 'PLANNED'} so system-generated
     * regenerable blocks are removed while {@code FOCUS}/{@code USER} blocks and already
     * {@code ACTIVE}/{@code SETTLED} work (which carry telemetry) are never touched. Idempotent: a
     * missing or non-eligible block is a no-op. Never deletes the executable the block scheduled.
     *
     * @param blockId the {@code core_time_block.id} mapped to the deleted EKEvent; never null
     * @return {@code true} if a row was deleted, {@code false} if none was eligible
     */
    boolean deletePlannedBlock(UUID blockId);
}
