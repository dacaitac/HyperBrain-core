package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.WigReservationPlan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The F1 WIG-portfolio policy of the deterministic floor (#6a): given the day's active MCIs, decides
 * which ones get an intocable lead-measure block and in what order, so required pace <em>orders</em>
 * the placement without ever collapsing the portfolio to a single "WIG of the day" (4DX D2). Pure
 * domain — no clock, no persistence; {@code now} enters only through the read port's precomputed
 * {@link MciWig#remainingFraction()}.
 *
 * <p><b>Ordering metric — required pace.</b> {@code (1 − aggregatedProgress) / max(remainingFraction, ε)}:
 * the further behind and the less time left, the higher the pace. A completed MCI yields pace 0 (it
 * sinks to the back); an overdue MCI is bounded by {@code maxRequiredPace} (no Infinity). The metric
 * normalises across cycle age, so a young MCI is never structurally excluded against an old one at the
 * same progress.
 *
 * <p><b>Selection.</b> An MCI with no lead measure is dropped with a
 * {@link ExclusionReason#WIG_WITHOUT_LEAD_MEASURE} alert (never a silent default). In the
 * <b>normal</b> case every remaining MCI is reserved — the order fixes placement only. In the
 * <b>degraded</b> case (the day's block budget is smaller than the portfolio) the order becomes a cut:
 * ordering is stabilised by a hysteresis margin (yesterday's MCI keeps priority unless another beats it
 * by {@code hysteresisMargin}) and rescued by a release valve (an MCI starved for
 * {@code degradedStreakThreshold} degraded days is force-promoted). A near-irrecoverable MCI whose
 * pace is capped does not out-rank an MCI still ahead of pace (the F5 guard leaves the capped tail last
 * among reachable WIGs — capping the numerator does exactly that).
 *
 * <p><b>Deterministic tiebreak</b> when required pace ties (Daniel, 2026-07-09):
 * {@code aggregatedProgress DESC} (finish the near-done first) → {@code endDate ASC} (nulls last) →
 * {@code mciCycleId ASC} (total reproducibility). {@code core_cycle} carries no {@code created_at}, so
 * the MCI id is the final key — it already guarantees a total, reproducible order.
 *
 * <p>Design pattern: single-algorithm domain service (Strategy avoided, YAGNI) — one fixed 4DX policy.
 */
public class WigPortfolioSelector {

    private final PlannerConstraints constraints;

    /** Creates a selector using the sanctioned default constraints. */
    public WigPortfolioSelector() {
        this(PlannerConstraints.DEFAULT);
    }

    /**
     * Creates a selector with explicit constraints (calibration seam).
     *
     * @param constraints the planner constraints; never null
     */
    public WigPortfolioSelector(PlannerConstraints constraints) {
        if (constraints == null) {
            throw new IllegalArgumentException("constraints must not be null");
        }
        this.constraints = constraints;
    }

    /**
     * The required pace of an MCI: {@code (1 − aggregatedProgress) / max(remainingFraction, ε)},
     * bounded by {@code maxRequiredPace}. A completed MCI yields 0.
     *
     * @param wig the MCI; never null
     * @return the required pace, in {@code [0, maxRequiredPace]}
     */
    public double requiredPace(MciWig wig) {
        if (wig.completed()) {
            return 0.0;
        }
        double remaining = Math.max(wig.remainingFraction(), constraints.pacePrecisionEpsilon());
        double pace = (1.0 - wig.aggregatedProgress()) / remaining;
        return Math.min(pace, constraints.maxRequiredPace());
    }

    /**
     * Selects the day's WIG portfolio: the MCIs to reserve (in placement order) plus those left out.
     * When the portfolio fits the budget every MCI with a lead measure is reserved and the order fixes
     * placement only; when it does not (the degraded case), the budget becomes a cut and the release
     * valve + hysteresis reshape the ranking so nothing starves and the choice is stable day to day.
     *
     * @param portfolio the day's active MCIs; never null
     * @param blockBudget the maximum number of WIG blocks the day admits (the WIG-first slice of the F6
     *                    quota); a budget at least the portfolio size means no cut
     * @return the reservation plan; never null
     */
    public WigReservationPlan select(List<MciWig> portfolio, int blockBudget) {
        if (portfolio == null) {
            throw new IllegalArgumentException("portfolio must not be null");
        }
        if (portfolio.isEmpty()) {
            return WigReservationPlan.empty();
        }

        List<ExcludedExecutable> excluded = new ArrayList<>();
        List<MciWig> reservable = new ArrayList<>();
        for (MciWig wig : portfolio) {
            if (wig.hasLeadMeasure()) {
                reservable.add(wig);
            } else {
                excluded.add(new ExcludedExecutable(
                    wig.mciCycleId(), ExclusionReason.WIG_WITHOUT_LEAD_MEASURE));
            }
        }

        List<MciWig> ranked = new ArrayList<>(reservable);
        ranked.sort(paceOrdering());

        int budget = Math.max(blockBudget, 0);
        if (ranked.size() <= budget) {
            return new WigReservationPlan(ranked, excluded);
        }

        // Degraded cut: fewer blocks than MCIs. Release valve and hysteresis reshape the ranking
        // before the top-{budget} slice (the normal, no-cut path returned above on the pace order).
        List<MciWig> ordered = applyDegradedPolicy(ranked, budget);

        List<MciWig> kept = new ArrayList<>(ordered.subList(0, budget));
        for (MciWig dropped : ordered.subList(budget, ordered.size())) {
            excluded.add(new ExcludedExecutable(
                dropped.mciCycleId(), ExclusionReason.WIG_BUDGET_EXCEEDED));
        }
        return new WigReservationPlan(kept, excluded);
    }

    /**
     * Reshapes the pace-ordered list for the degraded cut. Two stable, transitive adjustments applied
     * to the pace order:
     * <ol>
     *   <li><b>Release valve.</b> Any MCI starved for {@code degradedStreakThreshold} degraded days is
     *       force-promoted to the front (starved-longest first), breaking hysteresis so nothing
     *       starves indefinitely.</li>
     *   <li><b>Hysteresis.</b> Among the remaining MCIs, an MCI that held a block yesterday and sits
     *       just below the cut is promoted above the MCI just above it only when that MCI does not
     *       exceed its pace by more than {@code hysteresisMargin} — a clearly more-behind WIG still
     *       wins. This runs as a single bounded pass at the cut, keeping the F5 guard intact: a capped,
     *       near-irrecoverable MCI cannot leapfrog a reachable WIG (its pace is already ≤ the cap).</li>
     * </ol>
     */
    private List<MciWig> applyDegradedPolicy(List<MciWig> paceOrdered, int budget) {
        List<MciWig> starved = new ArrayList<>();
        List<MciWig> rest = new ArrayList<>();
        for (MciWig mci : paceOrdered) {
            (starved(mci) ? starved : rest).add(mci);
        }
        starved.sort(Comparator.comparingInt(MciWig::degradedDaysWithoutBlock).reversed()
            .thenComparing(tiebreak()));

        List<MciWig> ordered = new ArrayList<>(starved);
        ordered.addAll(rest);

        applyHysteresisAtCut(ordered, budget);
        return ordered;
    }

    /**
     * A single hysteresis swap at the cut boundary: if the MCI just below the cut held a block
     * yesterday and the MCI just above it does not beat its pace by more than {@code hysteresisMargin},
     * the sticky MCI keeps its place from yesterday. Bounded to the boundary pair so the order stays
     * total and reproducible.
     */
    private void applyHysteresisAtCut(List<MciWig> ordered, int budget) {
        if (budget <= 0 || budget >= ordered.size()) {
            return;
        }
        MciWig aboveCut = ordered.get(budget - 1);
        MciWig belowCut = ordered.get(budget);
        if (!belowCut.receivedBlockYesterday() || aboveCut.receivedBlockYesterday()) {
            return;
        }
        double margin = requiredPace(aboveCut) - requiredPace(belowCut);
        if (margin <= constraints.hysteresisMargin()) {
            ordered.set(budget - 1, belowCut);
            ordered.set(budget, aboveCut);
        }
    }

    private boolean starved(MciWig mci) {
        return mci.degradedDaysWithoutBlock() >= constraints.degradedStreakThreshold();
    }

    /** Required pace, highest first, then the deterministic tiebreak. */
    private Comparator<MciWig> paceOrdering() {
        return Comparator.comparingDouble(this::requiredPace).reversed().thenComparing(tiebreak());
    }

    /**
     * The deterministic tiebreak (Daniel, 2026-07-09): higher aggregated progress first (finish the
     * near-done), earlier end date next (nulls last), MCI id last for total reproducibility.
     */
    private static Comparator<MciWig> tiebreak() {
        return Comparator.comparingDouble(MciWig::aggregatedProgress).reversed()
            .thenComparing(MciWig::endDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(mci -> mci.mciCycleId().toString());
    }
}
