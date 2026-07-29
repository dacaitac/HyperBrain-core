package com.hyperbrain.sync.domain.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/*
 * Design pattern: Specification (a pure domain rule).
 * Reason: the area-containment invariant (ADR-036, catalogue DR-NN) is a single, side-effect-free
 * decision over candidate memberships, kept as a static domain policy so it is unit-testable without
 * infrastructure and reused wherever memberships are written (today: Notion inbound reconcile).
 */

/**
 * The area-containment rule of the {@code core_cycle_area} bridge (ADR-036, catalogue DR-NN): decides
 * which candidate {@code area_id}s a cycle may legitimately be linked to. A candidate is valid only when
 * <ul>
 *   <li>it is not the cycle itself (no self-reference — also guarded by the {@code core_cycle_area_no_self}
 *       DB CHECK), and
 *   <li>the referenced cycle exists and its type is {@code AREA} (there is no DB CHECK for this by
 *       design, R2: the domain is the authority, the DB is belt-and-suspenders).
 * </ul>
 *
 * <p>Invalid candidates are filtered out (not fatal), mirroring how the inbound sync omits an unmapped
 * or self-referential parent relation rather than wedging the whole ingestion. The perpetuity invariant
 * (an {@code AREA} carries no {@code end_date}) is enforced separately, on the AREA cycle itself, by the
 * inbound mapper and the {@code core_cycle_area_perpetual} DB CHECK.
 *
 * <p>Thread-safe: stateless, static methods only.
 */
public final class AreaContainmentPolicy {

    private static final String AREA = "AREA";

    private AreaContainmentPolicy() {
    }

    /**
     * Returns the subset of candidate AREA ids a cycle may be linked to, dropping self-references and
     * ids that do not resolve to an {@code AREA} cycle.
     *
     * @param cycleId        the owning (serving) cycle
     * @param candidateTypes the candidate {@code area_id}s mapped to the resolved {@code type} of the
     *                       cycle they reference ({@code null} type = the cycle does not exist locally);
     *                       never null. Iteration order is preserved for deterministic behaviour.
     * @return the valid AREA ids, insertion-ordered; empty when none qualify
     */
    public static Set<UUID> validate(UUID cycleId, Map<UUID, String> candidateTypes) {
        Set<UUID> valid = new LinkedHashSet<>();
        for (Map.Entry<UUID, String> candidate : candidateTypes.entrySet()) {
            UUID areaId = candidate.getKey();
            if (areaId.equals(cycleId)) {
                continue;
            }
            if (AREA.equals(candidate.getValue())) {
                valid.add(areaId);
            }
        }
        return valid;
    }
}
