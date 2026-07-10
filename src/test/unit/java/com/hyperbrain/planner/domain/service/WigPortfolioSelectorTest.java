package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.model.WigReservationPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("WigPortfolioSelector (F1 — required-pace portfolio, Comité 2026-07-09)")
class WigPortfolioSelectorTest {

    private final WigPortfolioSelector selector = new WigPortfolioSelector(PlannerConstraints.DEFAULT);

    private static final int UNBOUNDED = Integer.MAX_VALUE;

    @Test
    @DisplayName("required pace = (1 − progress) / max(remainingFraction, ε): behind + less time → higher")
    void required_pace_formula() {
        // 20% done, 25% of the window left → (1 − 0.2)/0.25 = 3.2.
        MciWig wig = wig(UUID.randomUUID(), UUID.randomUUID(), 0.2, 0.25);

        assertThat(selector.requiredPace(wig)).isCloseTo(3.2, within(1e-9));
    }

    @Test
    @DisplayName("F1.4: a completed MCI has pace 0 and sorts to the back")
    void completed_mci_has_zero_pace() {
        MciWig completed = new MciWig(UUID.randomUUID(), UUID.randomUUID(), 1.0, 0.5, true,
            LocalDate.of(2026, 12, 31), false, 0);

        assertThat(selector.requiredPace(completed)).isZero();
    }

    @Test
    @DisplayName("F1.4: an overdue MCI is bounded by ε (and the cap), never Infinity")
    void overdue_mci_is_bounded() {
        // remainingFraction at the tiny overdue floor; the ε floor (0.05) bounds the denominator, so a
        // fully-behind overdue MCI tops out at 1/ε = 20 — finite, and below the cap.
        MciWig overdue = wig(UUID.randomUUID(), UUID.randomUUID(), 0.0, 1e-6);
        PlannerConstraints d = PlannerConstraints.DEFAULT;

        double pace = selector.requiredPace(overdue);

        assertThat(pace).isFinite()
            .isCloseTo(1.0 / d.pacePrecisionEpsilon(), within(1e-9))
            .isLessThanOrEqualTo(d.maxRequiredPace());
    }

    @Test
    @DisplayName("F1.4: the pace cap bounds even a tiny ε (the second guard against Infinity)")
    void pace_cap_bounds_tiny_epsilon() {
        // With a much smaller ε the ε floor no longer bounds the pace, so the explicit cap does.
        PlannerConstraints tinyEps = new PlannerConstraints(
            5, 14, 36, 45, 1e-9, 100.0, 0.10, 3, 2, 4);
        WigPortfolioSelector capped = new WigPortfolioSelector(tinyEps);
        MciWig overdue = wig(UUID.randomUUID(), UUID.randomUUID(), 0.0, 1e-6);

        assertThat(capped.requiredPace(overdue)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("F1 defect (b): a recurring lead measure does not inflate the atraso (progress, not DONE/total)")
    void recurring_lead_measure_does_not_inflate_pace() {
        // Progress is read from the aggregated progress (fed from `progress`, not done/total counts),
        // so a recurring lead measure that is 70% along shows a moderate pace, not a maxed-out one.
        MciWig recurring = wig(UUID.randomUUID(), UUID.randomUUID(), 0.7, 0.5);

        double pace = selector.requiredPace(recurring);

        // (1 − 0.7)/0.5 = 0.6 — far from the cap; the recurrence did not inflate it.
        assertThat(pace).isCloseTo(0.6, within(1e-9));
        assertThat(pace).isLessThan(PlannerConstraints.DEFAULT.maxRequiredPace());
    }

    @Test
    @DisplayName("F1 defect (c): a young MCI is not structurally excluded vs an old one at equal progress")
    void young_and_old_mci_with_equal_progress_tie_on_pace() {
        // Same aggregated progress and same remaining fraction → identical pace regardless of age.
        MciWig young = wig(UUID.randomUUID(), UUID.randomUUID(), 0.5, 0.5);
        MciWig old = wig(UUID.randomUUID(), UUID.randomUUID(), 0.5, 0.5);

        assertThat(selector.requiredPace(young)).isEqualTo(selector.requiredPace(old));
    }

    @Test
    @DisplayName("normal case: every MCI with a lead measure is reserved; the order fixes placement only")
    void normal_case_reserves_all_ordered_by_pace() {
        MciWig behind = wig(UUID.randomUUID(), UUID.randomUUID(), 0.1, 0.2); // pace 4.5
        MciWig ahead = wig(UUID.randomUUID(), UUID.randomUUID(), 0.8, 0.5);  // pace 0.4

        WigReservationPlan plan = selector.select(List.of(ahead, behind), UNBOUNDED);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId)
            .containsExactly(behind.mciCycleId(), ahead.mciCycleId());
        assertThat(plan.excluded()).isEmpty();
    }

    @Test
    @DisplayName("F1 defect (a): an MCI with no lead measure is excluded with the 4DX-D2 alert")
    void no_lead_measure_mci_excluded_with_alert() {
        UUID mciId = UUID.randomUUID();
        MciWig noLead = new MciWig(mciId, null, 0.0, 1.0, false, null, false, 0);
        MciWig withLead = wig(UUID.randomUUID(), UUID.randomUUID(), 0.2, 0.5);

        WigReservationPlan plan = selector.select(List.of(noLead, withLead), UNBOUNDED);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId).containsExactly(withLead.mciCycleId());
        assertThat(plan.excluded())
            .containsExactly(new ExcludedExecutable(mciId, ExclusionReason.WIG_WITHOUT_LEAD_MEASURE));
        assertThat(plan.excludedFor(ExclusionReason.WIG_WITHOUT_LEAD_MEASURE)).containsExactly(mciId);
    }

    @Test
    @DisplayName("determinism: two MCIs with identical pace resolve reproducibly by the tiebreak (id last)")
    void identical_pace_ties_break_deterministically() {
        UUID lowId = new UUID(0L, 1L);
        UUID highId = new UUID(0L, 2L);
        // Identical pace and progress and end date → the final key is the MCI id (ascending).
        LocalDate end = LocalDate.of(2026, 12, 31);
        MciWig second = new MciWig(highId, UUID.randomUUID(), 0.5, 0.5, false, end, false, 0);
        MciWig first = new MciWig(lowId, UUID.randomUUID(), 0.5, 0.5, false, end, false, 0);

        WigReservationPlan a = selector.select(List.of(second, first), UNBOUNDED);
        WigReservationPlan b = selector.select(List.of(first, second), UNBOUNDED);

        assertThat(a.ordered()).extracting(MciWig::mciCycleId).containsExactly(lowId, highId);
        assertThat(b.ordered()).extracting(MciWig::mciCycleId).containsExactly(lowId, highId);
    }

    @Test
    @DisplayName("tiebreak: equal pace prefers higher aggregated progress (finish the near-done first)")
    void equal_pace_prefers_higher_progress() {
        // Different progress but tuned to the same pace: (1−0.6)/0.4 = 1.0 and (1−0.2)/0.8 = 1.0.
        MciWig nearDone = wig(UUID.randomUUID(), UUID.randomUUID(), 0.6, 0.4);
        MciWig early = wig(UUID.randomUUID(), UUID.randomUUID(), 0.2, 0.8);

        WigReservationPlan plan = selector.select(List.of(early, nearDone), UNBOUNDED);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId)
            .containsExactly(nearDone.mciCycleId(), early.mciCycleId());
    }

    @Test
    @DisplayName("degraded cut: budget 1 with 2 MCIs keeps the most-behind and excludes the other as WIG budget")
    void degraded_cut_keeps_most_behind() {
        MciWig behind = wig(UUID.randomUUID(), UUID.randomUUID(), 0.1, 0.2); // pace 4.5
        MciWig ahead = wig(UUID.randomUUID(), UUID.randomUUID(), 0.8, 0.5);  // pace 0.4

        WigReservationPlan plan = selector.select(List.of(ahead, behind), 1);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId).containsExactly(behind.mciCycleId());
        assertThat(plan.excluded())
            .containsExactly(new ExcludedExecutable(ahead.mciCycleId(), ExclusionReason.WIG_BUDGET_EXCEEDED));
    }

    @Test
    @DisplayName("F1.5 hysteresis (degraded only): yesterday's MCI keeps the slot within the 10% margin")
    void hysteresis_keeps_yesterdays_mci_within_margin() {
        // Just-above-cut MCI leads by less than the 10% margin; the sticky below-cut MCI (block
        // yesterday) keeps the slot. paceAbove − paceBelow = 0.05 ≤ 0.10.
        MciWig above = wig(UUID.randomUUID(), UUID.randomUUID(), 0.50, 1.0); // pace 0.50
        MciWig stickyBelow = new MciWig(UUID.randomUUID(), UUID.randomUUID(), 0.55, 1.0, false,
            LocalDate.of(2026, 12, 31), true, 0); // pace 0.45, held a block yesterday

        WigReservationPlan plan = selector.select(List.of(above, stickyBelow), 1);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId)
            .containsExactly(stickyBelow.mciCycleId());
    }

    @Test
    @DisplayName("F1.5 hysteresis: a clearly more-behind MCI beats yesterday's sticky one past the margin")
    void hysteresis_yields_to_a_clearly_more_behind_mci() {
        // paceAbove − paceBelow = 0.5 > 0.10 → the sticky MCI loses the slot.
        MciWig above = wig(UUID.randomUUID(), UUID.randomUUID(), 0.20, 1.0);  // pace 0.80
        MciWig stickyBelow = new MciWig(UUID.randomUUID(), UUID.randomUUID(), 0.70, 1.0, false,
            LocalDate.of(2026, 12, 31), true, 0); // pace 0.30, held a block yesterday

        WigReservationPlan plan = selector.select(List.of(above, stickyBelow), 1);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId).containsExactly(above.mciCycleId());
    }

    @Test
    @DisplayName("F1.5 release valve: an MCI starved for the streak threshold is force-promoted past hysteresis")
    void release_valve_promotes_starved_mci() {
        // A starved MCI (3 degraded days without a block) beats both a higher-pace MCI and a sticky one.
        MciWig higherPace = wig(UUID.randomUUID(), UUID.randomUUID(), 0.0, 0.5); // pace 2.0
        MciWig starved = new MciWig(UUID.randomUUID(), UUID.randomUUID(), 0.9, 1.0, false,
            LocalDate.of(2026, 12, 31), false, 3); // low pace but starved for 3 degraded days

        WigReservationPlan plan = selector.select(List.of(higherPace, starved), 1);

        assertThat(plan.ordered()).extracting(MciWig::mciCycleId).containsExactly(starved.mciCycleId());
        assertThat(plan.excluded())
            .containsExactly(new ExcludedExecutable(higherPace.mciCycleId(), ExclusionReason.WIG_BUDGET_EXCEEDED));
    }

    @Test
    @DisplayName("empty portfolio: an empty plan")
    void empty_portfolio() {
        assertThat(selector.select(List.of(), UNBOUNDED)).isEqualTo(WigReservationPlan.empty());
    }

    private static MciWig wig(UUID mciId, UUID leadMeasureId, double progress, double remainingFraction) {
        return new MciWig(mciId, leadMeasureId, progress, remainingFraction, false,
            LocalDate.of(2026, 12, 31), false, 0);
    }
}
