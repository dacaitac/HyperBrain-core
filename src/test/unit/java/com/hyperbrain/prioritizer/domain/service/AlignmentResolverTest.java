package com.hyperbrain.prioritizer.domain.service;

import com.hyperbrain.prioritizer.domain.model.AlignmentWeights;
import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext.AncestorLink;
import com.hyperbrain.prioritizer.domain.model.CycleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("AlignmentResolver (graded MCI alignment, Comité 2026-07-09)")
class AlignmentResolverTest {

    private static final UUID CYCLE = UUID.fromString("c1000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("c1000000-0000-0000-0000-000000000002");
    private static final double EPS = 1e-9;

    private final AlignmentResolver resolver = new AlignmentResolver();

    /** A context whose own type is TASK-neutral (PROJECT) unless the Coach cap is under test. */
    private static CycleAlignmentContext context(CycleType ownType, AncestorLink... ancestors) {
        return new CycleAlignmentContext(ownType, List.of(ancestors));
    }

    private static AncestorLink link(CycleType type, int distance) {
        return new AncestorLink(type, distance);
    }

    @Nested
    @DisplayName("band weight × distance decay")
    class Grading {

        @Test
        @DisplayName("MCI ancestor at distance 0 yields the full 1.0")
        void mci_at_distance_zero_is_full() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.MCI, link(CycleType.MCI, 0))));

            assertThat(alignment).isCloseTo(1.0, within(EPS));
        }

        @Test
        @DisplayName("MCI at distance 1 keeps δ=1.0 → 1.0")
        void mci_at_distance_one_no_decay() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.MCI, 1))));

            assertThat(alignment).isCloseTo(1.0, within(EPS));
        }

        @Test
        @DisplayName("MCI at distance 2 applies δ=0.9 → 0.9")
        void mci_at_distance_two_decays() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.MCI, 2))));

            assertThat(alignment).isCloseTo(0.9, within(EPS));
        }

        @Test
        @DisplayName("MCI at distance 3+ applies δ=0.8 → 0.8")
        void mci_at_distance_far_decays_to_floor() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.MCI, 5))));

            assertThat(alignment).isCloseTo(0.8, within(EPS));
        }

        @Test
        @DisplayName("GOAL ancestor uses its 0.5 band × decay")
        void goal_band_applied() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.GOAL, 2))));

            // 0.5 * δ(2)=0.9 = 0.45
            assertThat(alignment).isCloseTo(0.45, within(EPS));
        }

        @Test
        @DisplayName("OBJECTIVE and PROJECT bands are 0.4 and 0.3")
        void objective_and_project_bands() {
            double objective = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.OBJECTIVE, 0))));
            double project = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.PROJECT, 0))));

            assertThat(objective).isCloseTo(0.4, within(EPS));
            assertThat(project).isCloseTo(0.3, within(EPS));
        }

        @Test
        @DisplayName("PHASE maps to the PROJECT band (0.3) — reported mapping")
        void phase_maps_to_project_band() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.PHASE, 0))));

            assertThat(alignment).isCloseTo(0.3, within(EPS));
        }
    }

    @Nested
    @DisplayName("max over ancestors")
    class MaxOverAncestors {

        @Test
        @DisplayName("MCI is dominant: it wins over any non-MCI ancestor at any distance")
        void mci_dominant_over_non_mci() {
            // MCI far (0.8) still beats a GOAL adjacent (0.5).
            double alignment = resolver.resolve(CYCLE, Map.of(CYCLE, context(CycleType.PROJECT,
                link(CycleType.GOAL, 0), link(CycleType.MCI, 6))));

            assertThat(alignment).isCloseTo(0.8, within(EPS));
        }

        @Test
        @DisplayName("takes the max across several active ancestors")
        void takes_max_across_ancestors() {
            double alignment = resolver.resolve(CYCLE, Map.of(CYCLE, context(CycleType.PROJECT,
                link(CycleType.PROJECT, 0),   // 0.3
                link(CycleType.OBJECTIVE, 1), // 0.4
                link(CycleType.GOAL, 2))));   // 0.5 * 0.9 = 0.45

            assertThat(alignment).isCloseTo(0.45, within(EPS));
        }

        @Test
        @DisplayName("closer MCI dominates a farther MCI (larger δ)")
        void closer_mci_dominates_farther() {
            double alignment = resolver.resolve(CYCLE, Map.of(CYCLE, context(CycleType.PROJECT,
                link(CycleType.MCI, 5),   // 0.8
                link(CycleType.MCI, 1)))); // 1.0

            assertThat(alignment).isCloseTo(1.0, within(EPS));
        }
    }

    @Nested
    @DisplayName("no active ancestor / orphan")
    class NoAlignment {

        @Test
        @DisplayName("no active ancestor: alignment is 0.0")
        void no_active_ancestor_is_zero() {
            double alignment = resolver.resolve(CYCLE, Map.of(CYCLE, context(CycleType.PROJECT)));

            assertThat(alignment).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("cycle not in the context map: alignment is 0.0")
        void cycle_absent_from_map_is_zero() {
            double alignment = resolver.resolve(OTHER,
                Map.of(CYCLE, context(CycleType.MCI, link(CycleType.MCI, 0))));

            assertThat(alignment).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("executable with no cycle: alignment is 0.0")
        void null_cycle_is_zero() {
            double alignment = resolver.resolve(null,
                Map.of(CYCLE, context(CycleType.MCI, link(CycleType.MCI, 0))));

            assertThat(alignment).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("empty context map: everything is 0.0")
        void empty_map_is_zero() {
            assertThat(resolver.resolve(CYCLE, Map.of())).isCloseTo(0.0, within(EPS));
        }
    }

    @Nested
    @DisplayName("Coach cap: own ROUTINE cycle cannot inherit a high value")
    class CoachCap {

        @Test
        @DisplayName("a ROUTINE cycle hanging off an MCI is capped at W(ROUTINE)·δ(0) = 0.15")
        void routine_under_mci_is_capped() {
            // Without the cap the MCI ancestor would grant 1.0; the cap holds it to 0.15.
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.ROUTINE, link(CycleType.MCI, 1))));

            assertThat(alignment).isCloseTo(0.15, within(EPS));
        }

        @Test
        @DisplayName("the cap never raises a value below its own band ceiling")
        void cap_does_not_raise() {
            // A ROUTINE whose only active ancestor is a ROUTINE grandparent decayed under 0.15.
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.ROUTINE, link(CycleType.ROUTINE, 2))));

            // 0.15 * δ(2)=0.9 = 0.135, already under the 0.15 ceiling: cap is a no-op.
            assertThat(alignment).isCloseTo(0.135, within(EPS));
        }

        @Test
        @DisplayName("the cap applies only to ROUTINE own cycles, not to a PROJECT under an MCI")
        void cap_only_for_routine_own_type() {
            double alignment = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.MCI, 1))));

            assertThat(alignment).isCloseTo(1.0, within(EPS));
        }
    }

    @Nested
    @DisplayName("range and calibration")
    class RangeAndCalibration {

        @Test
        @DisplayName("every resolved value stays within [0, 1]")
        void stays_in_unit_interval() {
            double high = resolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.MCI, link(CycleType.MCI, 0))));
            double low = resolver.resolve(CYCLE, Map.of(CYCLE, context(CycleType.PROJECT)));

            assertThat(high).isBetween(0.0, 1.0);
            assertThat(low).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("custom AlignmentWeights recalibrate bands and decay")
        void custom_weights_applied() {
            AlignmentWeights flat = new AlignmentWeights(
                Map.of(CycleType.MCI, 0.6, CycleType.GOAL, 0.6, CycleType.OBJECTIVE, 0.6,
                    CycleType.PROJECT, 0.6, CycleType.PHASE, 0.6, CycleType.ROUTINE, 0.6),
                1.0, 1.0, 1.0);
            AlignmentResolver flatResolver = new AlignmentResolver(flat);

            double alignment = flatResolver.resolve(CYCLE,
                Map.of(CYCLE, context(CycleType.PROJECT, link(CycleType.MCI, 3))));

            assertThat(alignment).isCloseTo(0.6, within(EPS));
        }
    }
}
