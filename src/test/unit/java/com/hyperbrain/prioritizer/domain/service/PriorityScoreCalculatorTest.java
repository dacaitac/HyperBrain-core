package com.hyperbrain.prioritizer.domain.service;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext.AncestorLink;
import com.hyperbrain.prioritizer.domain.model.CycleType;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.prioritizer.domain.model.PriorityWeights;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("PriorityScoreCalculator (Priority Score v2, normalize-then-weight)")
class PriorityScoreCalculatorTest {

    private static final UUID E1 = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    private static final UUID E2 = UUID.fromString("e0000000-0000-0000-0000-000000000002");
    private static final UUID E3 = UUID.fromString("e0000000-0000-0000-0000-000000000003");
    private static final UUID MCI_CYCLE = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final double EPS = 1e-9;

    private final PriorityScoreCalculator calculator = new PriorityScoreCalculator();

    @Nested
    @DisplayName("factor normalization to [0, 1]")
    class Normalization {

        @Test
        @DisplayName("all factors at their maximum yield P = 1.0 (weights sum to 1)")
        void all_max_factors_yield_one() {
            // impact 5 -> 1.0, urgency 6 -> 1.0, effort 0 -> 1.0, alignment 1.0
            PriorityScore score = calculator.score(factors(E1, null, 5, 6.0, 0.0), 1.0);

            assertThat(score.score()).isCloseTo(1.0, within(EPS));
        }

        @Test
        @DisplayName("all factors at their minimum yield P = 0.0")
        void all_min_factors_yield_zero() {
            // impact 1 -> 0.0, urgency 0 -> 0.0, effort 5 -> 0.0, alignment 0.0
            PriorityScore score = calculator.score(factors(E1, null, 1, 0.0, 5.0), 0.0);

            assertThat(score.score()).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("impact is normalized (I - 1) / 4 on the ordinal 1-5 scale")
        void impact_normalized_ordinal() {
            // impact 5 -> 4/4 = 1.0; only impact contributes: 1.0 * 0.4 = 0.4
            PriorityScore score = calculator.score(factors(E1, null, 5, 0.0, 5.0), 0.0);

            assertThat(score.score()).isCloseTo(0.4, within(EPS));
        }

        @Test
        @DisplayName("urgency is normalized min(U, 6) / 6")
        void urgency_normalized() {
            // urgency 3 -> 0.5; only urgency contributes: 0.5 * 0.3
            PriorityScore score = calculator.score(factors(E1, null, 1, 3.0, 5.0), 0.0);

            assertThat(score.score()).isCloseTo(0.5 * 0.3, within(EPS));
        }

        @Test
        @DisplayName("overdue urgency above 6 is capped at 6 (normalized to 1.0)")
        void urgency_overdue_is_capped() {
            // urgency 9 -> capped 6 -> 1.0; only urgency contributes: 1.0 * 0.3
            PriorityScore score = calculator.score(factors(E1, null, 1, 9.0, 5.0), 0.0);

            assertThat(score.score()).isCloseTo(0.3, within(EPS));
        }

        @Test
        @DisplayName("effort is inverted (5 - E) / 5 so lower effort scores higher")
        void effort_inverted() {
            // effort 2 -> (5-2)/5 = 0.6; only effort contributes: 0.6 * 0.1
            PriorityScore score = calculator.score(factors(E1, null, 1, 0.0, 2.0), 0.0);

            assertThat(score.score()).isCloseTo(0.6 * 0.1, within(EPS));
            assertThat(score.effortInv()).isCloseTo(0.6, within(EPS));
        }

        @Test
        @DisplayName("the v2 fix: normalizing gives alignment its full 20% weight")
        void alignment_carries_full_weight() {
            // Only alignment set to its max; it must contribute exactly 0.2, not the ~4% of v1.
            PriorityScore score = calculator.score(factors(E1, MCI_CYCLE, 1, 0.0, 5.0), 1.0);

            assertThat(score.score()).isCloseTo(0.2, within(EPS));
        }
    }

    @Nested
    @DisplayName("weighted combination")
    class Weighting {

        @Test
        @DisplayName("combines the four normalized factors by their weights")
        void combines_all_factors() {
            // impact 5 -> 1.0 * 0.4 = 0.4
            // urgency 3 -> 0.5 * 0.3 = 0.15
            // effort 4 -> 0.2 * 0.1 = 0.02
            // alignment 1.0 -> 1.0 * 0.2 = 0.2
            // total = 0.77
            PriorityScore score = calculator.score(factors(E1, MCI_CYCLE, 5, 3.0, 4.0), 1.0);

            assertThat(score.score()).isCloseTo(0.77, within(EPS));
        }

        @Test
        @DisplayName("custom weights are applied instead of the defaults")
        void custom_weights_applied() {
            // Weight everything on urgency; urgency 6 -> 1.0 -> P = 1.0.
            PriorityScoreCalculator urgencyOnly = new PriorityScoreCalculator(
                new PriorityWeights(0.0, 1.0, 0.0, 0.0), new AlignmentResolver());

            PriorityScore score = urgencyOnly.score(factors(E1, null, 5, 6.0, 0.0), 1.0);

            assertThat(score.score()).isCloseTo(1.0, within(EPS));
        }
    }

    @Nested
    @DisplayName("missing profile factors")
    class MissingFactors {

        @Test
        @DisplayName("null impact floors to 0 (no impact contribution)")
        void null_impact_floors_to_zero() {
            // urgency 6 -> 1.0 * 0.3 = 0.3 is the only contribution
            PriorityScore score = calculator.score(factors(E1, null, null, 6.0, 5.0), 0.0);

            assertThat(score.score()).isCloseTo(0.3, within(EPS));
        }

        @Test
        @DisplayName("null effort floors to 0 (no Quick-Win contribution)")
        void null_effort_floors_to_zero() {
            // impact 5 -> 1.0 * 0.4 = 0.4 is the only contribution; effortInv is 0.0
            PriorityScore score = calculator.score(factors(E1, null, 5, 0.0, null), 0.0);

            assertThat(score.score()).isCloseTo(0.4, within(EPS));
            assertThat(score.effortInv()).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("all profile factors null still scores from urgency alone")
        void all_null_scores_from_urgency() {
            PriorityScore score = calculator.score(factors(E1, null, null, 0.0, null), 0.0);

            assertThat(score.score()).isCloseTo(0.0, within(EPS));
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("orders executables by descending Priority Score")
        void orders_by_descending_score() {
            List<ExecutableFactors> input = List.of(
                factors(E1, null, 1, 0.0, 5.0),   // P = 0.0
                factors(E2, null, 5, 6.0, 0.0),   // P = 0.4 + 0.3 + 0.1 = 0.8
                factors(E3, null, 4, 3.0, 3.0));  // middle

            List<PriorityScore> ranked = calculator.rank(input, Map.of());

            assertThat(ranked).extracting(PriorityScore::executableId)
                .containsExactly(E2, E3, E1);
        }

        @Test
        @DisplayName("applies MCI alignment during ranking (aligned executable rises)")
        void applies_alignment_during_ranking() {
            // Both identical except E1's cycle is a fully-aligned MCI -> +0.2 -> ranks first.
            List<ExecutableFactors> input = List.of(
                factors(E2, null, 4, 3.0, 3.0),
                factors(E1, MCI_CYCLE, 4, 3.0, 3.0));

            List<PriorityScore> ranked = calculator.rank(input, fullyAlignedMci());

            assertThat(ranked).extracting(PriorityScore::executableId)
                .containsExactly(E1, E2);
        }

        @Test
        @DisplayName("no MCI active: alignment adds nothing, ranking falls to the other factors")
        void no_mci_active_alignment_is_neutral() {
            List<ExecutableFactors> input = List.of(
                factors(E1, MCI_CYCLE, 4, 3.0, 3.0),
                factors(E2, null, 5, 6.0, 0.0));

            List<PriorityScore> ranked = calculator.rank(input, Map.of());

            // Without alignment, E2's higher raw factors win.
            assertThat(ranked).extracting(PriorityScore::executableId)
                .containsExactly(E2, E1);
        }

        @Test
        @DisplayName("score tie is broken by the inverted-effort factor (Quick Wins first)")
        void tie_broken_by_effort_inv() {
            // Same impact/urgency/alignment, differing only in effort: lower effort -> higher effortInv
            // -> must rank first despite the equal total score.
            ExecutableFactors highEffort = factors(E1, null, 4, 3.0, 4.0); // effortInv 0.2
            ExecutableFactors lowEffort = factors(E2, null, 4, 3.0, 1.0);  // effortInv 0.8

            // Neutralize the effort weight so the totals tie, forcing the tie-break to decide.
            PriorityScoreCalculator noEffortWeight = new PriorityScoreCalculator(
                new PriorityWeights(0.4, 0.3, 0.0, 0.2), new AlignmentResolver());

            List<PriorityScore> ranked = noEffortWeight.rank(List.of(highEffort, lowEffort), Map.of());

            assertThat(ranked.get(0).score()).isCloseTo(ranked.get(1).score(), within(EPS));
            assertThat(ranked).extracting(PriorityScore::executableId)
                .containsExactly(E2, E1);
        }

        @Test
        @DisplayName("full tie is broken deterministically by executable id")
        void full_tie_broken_by_id() {
            ExecutableFactors a = factors(E1, null, 4, 3.0, 3.0);
            ExecutableFactors b = factors(E2, null, 4, 3.0, 3.0);

            List<PriorityScore> ranked = calculator.rank(List.of(b, a), Map.of());

            assertThat(ranked).extracting(PriorityScore::executableId)
                .containsExactly(E1, E2);
        }

        @Test
        @DisplayName("empty input ranks to an empty list")
        void empty_input_ranks_empty() {
            assertThat(calculator.rank(List.of(), Map.of())).isEmpty();
        }
    }

    private static ExecutableFactors factors(
        UUID id, UUID cycleId, Integer impact, double urgencyRaw, Double effort) {
        return new ExecutableFactors(id, cycleId, impact, urgencyRaw, effort);
    }

    /** A context where MCI_CYCLE is itself an active MCI at distance 0 (full alignment, 1.0). */
    private static Map<UUID, CycleAlignmentContext> fullyAlignedMci() {
        return Map.of(MCI_CYCLE, new CycleAlignmentContext(
            CycleType.MCI, List.of(new AncestorLink(CycleType.MCI, 0))));
    }
}
