package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One WIG of the day's portfolio: an {@code ACTIVE MCI} cycle ({@code CoreCycle type=MCI}) and the
 * {@code LEAD_MEASURE} executable ({@code CoreExecutable type=LEAD_MEASURE}) to reserve for it. The
 * portfolio is the set of active MCIs (1–3), <b>stable across the cycle</b> — the floor does not pick
 * "the WIG of the day"; it reserves one intocable lead-measure block for <em>each</em> active MCI
 * that requires one, WIG-first, and lets required pace only <em>order</em> the placement (4DX D2).
 *
 * <p><b>Aggregated progress</b> is the {@code estimated_minutes}-weighted mean of the effective
 * progress of the MCI's root executables (the read port computes it once per run; see
 * {@code JdbcPlannerStateRepository}). {@code remainingFraction} is
 * {@code (end_date − now)/(end_date − start_date)}, already clamped to the borderline defaults by the
 * read port. From these the {@link WigPortfolioSelector} derives the ordering metric — required pace
 * {@code (1 − aggregatedProgress) / max(remainingFraction, ε)} — capped to avoid an overdue MCI
 * producing Infinity.
 *
 * <p>An MCI with no lead measure ({@code leadMeasureId == null}) violates 4DX D2 (a WIG with no lead
 * measure): it is excluded from the reservation and raises an alert; it never yields a silent default.
 *
 * @param mciCycleId       the active MCI cycle; never null
 * @param leadMeasureId    the LEAD_MEASURE executable to reserve, or null when the MCI has none
 * @param aggregatedProgress the effort-weighted mean effective progress in {@code [0, 1]}; 0 when the
 *                         MCI has no aggregatable executables (it competes, all still to do)
 * @param remainingFraction the fraction of the cycle window still ahead, in {@code (0, 1]} — 1.0 when
 *                         the MCI carries no {@code start_date}/{@code end_date} (no temporal pressure)
 * @param completed        true when the MCI is COMPLETED or its aggregated progress reached 1.0 (it
 *                         sinks to the back with pace 0)
 * @param endDate          the cycle end date, for the deterministic tiebreak; null when open-ended
 * @param receivedBlockYesterday true when this MCI's lead measure held a planner block yesterday
 *                         (hysteresis input, consulted only in degraded mode)
 * @param degradedDaysWithoutBlock consecutive recent days the MCI went without a reserved block
 *                         (release-valve input, consulted only in degraded mode)
 */
public record MciWig(
    UUID mciCycleId,
    UUID leadMeasureId,
    double aggregatedProgress,
    double remainingFraction,
    boolean completed,
    LocalDate endDate,
    boolean receivedBlockYesterday,
    int degradedDaysWithoutBlock
) {

    public MciWig {
        if (mciCycleId == null) {
            throw new IllegalArgumentException("mciCycleId must not be null");
        }
        if (aggregatedProgress < 0.0 || aggregatedProgress > 1.0) {
            throw new IllegalArgumentException(
                "aggregatedProgress must be in [0, 1]: " + aggregatedProgress);
        }
        if (remainingFraction <= 0.0 || remainingFraction > 1.0) {
            throw new IllegalArgumentException(
                "remainingFraction must be in (0, 1]: " + remainingFraction);
        }
        if (degradedDaysWithoutBlock < 0) {
            throw new IllegalArgumentException(
                "degradedDaysWithoutBlock must be non-negative: " + degradedDaysWithoutBlock);
        }
    }

    /** @return true when this MCI has a lead measure to reserve (a valid 4DX D2 WIG). */
    public boolean hasLeadMeasure() {
        return leadMeasureId != null;
    }
}
