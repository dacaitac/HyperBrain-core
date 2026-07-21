package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.AdherenceThresholds;
import com.hyperbrain.planner.domain.model.DailyAdherenceReport;
import com.hyperbrain.planner.domain.model.DailyBlockObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the H0 rollup formula (#17): adherence with a minimum-minutes temporal tolerance,
 * the WIG-hit lead measure, and the abandoned flag (low adherence with zero replans). Pure domain,
 * no Spring context.
 */
@DisplayName("AdherenceCalculator — H0 adherence + behavioral lead measures")
class AdherenceCalculatorTest {

    private static final int TOLERANCE_MINUTES = 5;
    private static final double ABANDONMENT_THRESHOLD = 0.5;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 20);
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final AdherenceCalculator calculator =
        new AdherenceCalculator(new AdherenceThresholds(TOLERANCE_MINUTES, ABANDONMENT_THRESHOLD));

    private static DailyBlockObservation task(Integer actualMinutes) {
        return new DailyBlockObservation(false, actualMinutes);
    }

    private static DailyBlockObservation wig(Integer actualMinutes) {
        return new DailyBlockObservation(true, actualMinutes);
    }

    private DailyAdherenceReport compute(List<DailyBlockObservation> blocks, int replanCount) {
        return calculator.compute(DAY, ZONE, blocks, replanCount, Boolean.TRUE);
    }

    @Test
    @DisplayName("adherence is the fraction of blocks executed past the tolerance")
    void adherence_is_executed_fraction() {
        // Given: 4 planned blocks, 3 executed (25, 60, 10 min) and 1 never run (null)
        List<DailyBlockObservation> blocks = List.of(task(25), task(60), task(10), task(null));

        // When
        DailyAdherenceReport report = compute(blocks, 0);

        // Then
        assertThat(report.blocksPlanned()).isEqualTo(4);
        assertThat(report.blocksExecuted()).isEqualTo(3);
        assertThat(report.adherence()).isCloseTo(0.75, within(1e-9));
        assertThat(report.date()).isEqualTo(DAY);
        assertThat(report.zone()).isEqualTo(ZONE);
        assertThat(report.ritualCompleted()).isTrue();
    }

    @Test
    @DisplayName("a block settled below the tolerance does not count as executed")
    void block_below_tolerance_is_not_executed() {
        // Given: one block ran only 4 min (below the 5-min tolerance), the other 30 min
        List<DailyBlockObservation> blocks = List.of(task(4), task(30));

        // When
        DailyAdherenceReport report = compute(blocks, 0);

        // Then
        assertThat(report.blocksExecuted()).isEqualTo(1);
        assertThat(report.adherence()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("a block settled exactly at the tolerance counts as executed")
    void block_at_tolerance_boundary_is_executed() {
        DailyAdherenceReport report = compute(List.of(task(TOLERANCE_MINUTES)), 0);

        assertThat(report.blocksExecuted()).isEqualTo(1);
        assertThat(report.adherence()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("an empty day yields zero adherence and is not abandoned")
    void empty_day_is_zero_and_not_abandoned() {
        DailyAdherenceReport report = compute(List.of(), 0);

        assertThat(report.blocksPlanned()).isZero();
        assertThat(report.blocksExecuted()).isZero();
        assertThat(report.adherence()).isZero();
        assertThat(report.wigHit()).isFalse();
        assertThat(report.abandoned()).isFalse();
    }

    @Test
    @DisplayName("wig_hit is true when the reserved WIG block was executed")
    void wig_hit_when_wig_executed() {
        DailyAdherenceReport report = compute(List.of(wig(30), task(2)), 0);

        assertThat(report.wigHit()).isTrue();
    }

    @Test
    @DisplayName("wig_hit is false when the WIG block was not executed past the tolerance")
    void wig_hit_false_when_wig_not_executed() {
        // WIG barely started (below tolerance) and a plain task fully executed
        DailyAdherenceReport report = compute(List.of(wig(3), task(60)), 0);

        assertThat(report.wigHit()).isFalse();
    }

    @Test
    @DisplayName("a low-adherence day with zero replans is abandoned")
    void low_adherence_without_replan_is_abandoned() {
        // adherence 1/4 = 0.25 < 0.5 threshold, no replans
        DailyAdherenceReport report = compute(List.of(task(30), task(null), task(null), task(null)), 0);

        assertThat(report.adherence()).isCloseTo(0.25, within(1e-9));
        assertThat(report.abandoned()).isTrue();
    }

    @Test
    @DisplayName("a low-adherence day with a replan is re-adjusted, not abandoned")
    void low_adherence_with_replan_is_not_abandoned() {
        DailyAdherenceReport report = compute(List.of(task(30), task(null), task(null), task(null)), 1);

        assertThat(report.adherence()).isCloseTo(0.25, within(1e-9));
        assertThat(report.replanCount()).isEqualTo(1);
        assertThat(report.abandoned()).isFalse();
    }

    @Test
    @DisplayName("a day meeting the adherence threshold is not abandoned even with zero replans")
    void adherence_at_threshold_is_not_abandoned() {
        // adherence 1/2 = 0.5 is not below the 0.5 threshold
        DailyAdherenceReport report = compute(List.of(task(30), task(null)), 0);

        assertThat(report.adherence()).isCloseTo(0.5, within(1e-9));
        assertThat(report.abandoned()).isFalse();
    }
}
