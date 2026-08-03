package com.hyperbrain.sync.domain.port.out;

import java.util.Set;
import java.util.UUID;

/**
 * Outbound port: persistence of the {@code core_cycle_area} M:N bridge (ADR-036) — the life AREAs a
 * commitment cycle serves. Tenancy is derived through the cycle FKs (no own {@code user_id}), mirroring
 * {@code core_time_block_member}.
 */
public interface CoreCycleAreaRepository {

    /**
     * Replaces the whole set of AREA memberships of one cycle (full mirror of the Notion {@code Areas}
     * relation, ADR-036): existing rows for {@code cycleId} are removed and the given ones inserted, so
     * an area a person removed in Notion disappears here too. Runs inside the ingestion transaction.
     *
     * @param cycleId the owning (serving) cycle
     * @param areaIds the AREA cycle ids to link; empty clears every membership of the cycle. The caller
     *                (containment rule) guarantees each is a valid, non-self AREA reference
     */
    void replaceMembershipsForCycle(UUID cycleId, Set<UUID> areaIds);

    /**
     * Reads the AREA cycle ids a cycle currently serves.
     *
     * @param cycleId the owning cycle
     * @return the linked AREA ids; empty when the cycle serves none
     */
    Set<UUID> findAreaIdsByCycle(UUID cycleId);
}
