package com.hyperbrain.planner.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of the F1 portfolio selection: which active MCIs get a reserved lead-measure block this
 * day, in placement order, plus the MCIs left out with the reason. The generator reserves one intocable
 * block per {@link #ordered() ordered} MCI (WIG-first), and surfaces every {@link #excluded() excluded}
 * MCI on the agenda (legibilidad obligatoria — a WIG is never dropped silently).
 *
 * <p>Ordering (required pace, then the deterministic tiebreak) governs which MCIs win scarce blocks in
 * the degraded case; in the normal case every MCI with a lead measure is reserved, and the order only
 * fixes placement. Excluded entries carry {@link ExclusionReason#WIG_WITHOUT_LEAD_MEASURE} (4DX D2
 * alert) or {@link ExclusionReason#WIG_BUDGET_EXCEEDED} (degraded budget cut).
 *
 * @param ordered  the MCIs to reserve, in placement order (most-behind pace first); never null
 * @param excluded the MCIs left off the reservation, keyed by MCI cycle id, with the reason; never null
 */
public record WigReservationPlan(List<MciWig> ordered, List<ExcludedExecutable> excluded) {

    public WigReservationPlan {
        ordered = ordered == null ? List.of() : List.copyOf(ordered);
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
    }

    /** @return an empty plan (no active MCIs). */
    public static WigReservationPlan empty() {
        return new WigReservationPlan(List.of(), List.of());
    }

    /** @return the MCI cycle ids excluded with the given reason, for the agenda's alert channel. */
    public List<UUID> excludedFor(ExclusionReason reason) {
        return excluded.stream()
            .filter(e -> e.reason() == reason)
            .map(ExcludedExecutable::executableId)
            .toList();
    }
}
