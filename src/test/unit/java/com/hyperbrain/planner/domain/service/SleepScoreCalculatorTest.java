package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.SleepScoreResult;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SleepScoreCalculator (ADR-016 v1.4.0 sleep_score formula)")
class SleepScoreCalculatorTest {

    private static final OffsetDateTime BEDTIME = OffsetDateTime.parse("2026-07-10T22:00:00Z");
    private static final double EPS = 0.02;

    private final SleepScoreCalculator calculator = new SleepScoreCalculator();

    /** A session with the given time-in-bed window and stage durations (inBed residual = 0). */
    private static SleepStageSample sample(long tibSeconds, long core, long deep, long rem,
                                           long unspecified, long awake) {
        return new SleepStageSample(BEDTIME, BEDTIME.plusSeconds(tibSeconds),
            0, core, deep, rem, unspecified, awake);
    }

    @Test
    @DisplayName("an ideal night (8h, SE≥90%, deep 18%, REM 22%, WASO 10min) scores 100 with full confidence")
    void ideal_night_scores_100() {
        // TST 8h = 28800s (core 17280, deep 5184=18%, rem 6336=22%); TIB 8.5h → SE 94%; WASO 10min.
        SleepScoreResult result = calculator.score(sample(30600, 17280, 5184, 6336, 0, 600));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.lowConfidence()).isFalse();
        assertThat(result.durationSubScore()).isEqualTo(100.0);
        assertThat(result.efficiencySubScore()).isEqualTo(100.0);
        assertThat(result.deepSubScore()).isCloseTo(100.0, within(EPS));
        assertThat(result.remSubScore()).isCloseTo(100.0, within(EPS));
        assertThat(result.wasoSubScore()).isCloseTo(100.0, within(EPS));
        assertThat(result.tstHours()).isCloseTo(8.0, within(EPS));
        assertThat(result.efficiency()).isCloseTo(0.9412, within(0.001));
    }

    @Test
    @DisplayName("no stage breakdown: only Duration + Efficiency, renormalized 60/40, low confidence, never 0")
    void no_phase_breakdown_renormalizes_60_40() {
        // TST 8h as unspecified only (no core/deep/rem); TIB 10h → SE 80% → efficiency sub 33.3.
        SleepScoreResult result = calculator.score(sample(36000, 0, 0, 0, 28800, 0));

        assertThat(result.lowConfidence()).isTrue();
        assertThat(result.deepSubScore()).isNull();
        assertThat(result.remSubScore()).isNull();
        assertThat(result.wasoSubScore()).isNull();
        assertThat(result.durationSubScore()).isEqualTo(100.0);
        assertThat(result.efficiencySubScore()).isCloseTo(33.333, within(EPS));
        // 0.6*100 + 0.4*33.333 = 73.3 → 73 (not the 55 the raw 45/30 weights would give).
        assertThat(result.score()).isEqualTo(73);
    }

    @Test
    @DisplayName("the 9–10h plateau does not penalize duration")
    void plateau_9_to_10h_not_penalized() {
        // TST 9.5h with a stage breakdown; duration sub stays flat at 100.
        SleepScoreResult result = calculator.score(sample(36000, 20520, 6156, 7524, 0, 600));

        assertThat(result.tstHours()).isCloseTo(9.5, within(EPS));
        assertThat(result.durationSubScore()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("oversleeping beyond 10h is penalized only very gently")
    void oversleep_beyond_10h_gentle_penalty() {
        // TST 11h → duration sub = 100 - (11-10)*15 = 85.
        SleepScoreResult result = calculator.score(sample(41400, 23760, 7128, 8712, 0, 600));

        assertThat(result.tstHours()).isCloseTo(11.0, within(EPS));
        assertThat(result.durationSubScore()).isCloseTo(85.0, within(EPS));
    }

    @Test
    @DisplayName("efficiency is full at 90% and zero at 75%")
    void efficiency_curve_edges() {
        // SE exactly 90%: TST 28800 / TIB 32000.
        SleepScoreResult full = calculator.score(sample(32000, 17280, 5184, 6336, 0, 600));
        assertThat(full.efficiency()).isCloseTo(0.90, within(0.001));
        assertThat(full.efficiencySubScore()).isEqualTo(100.0);

        // SE exactly 75%: TST 28800 / TIB 38400.
        SleepScoreResult zero = calculator.score(sample(38400, 17280, 5184, 6336, 0, 600));
        assertThat(zero.efficiency()).isCloseTo(0.75, within(0.001));
        assertThat(zero.efficiencySubScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("fragmentation ramps down: WASO 40min scores 50")
    void waso_ramp() {
        // WASO 40min between the 20min (full) and 60min (zero) edges → (60-40)/40*100 = 50.
        SleepScoreResult result = calculator.score(sample(30600, 17280, 5184, 6336, 0, 2400));

        assertThat(result.wasoMinutes()).isCloseTo(40.0, within(EPS));
        assertThat(result.wasoSubScore()).isCloseTo(50.0, within(EPS));
    }

    @Test
    @DisplayName("deep-sleep below the band falls off: 5% N3 scores ~27")
    void deep_band_falloff() {
        // deep 5% (1440s of 28800) between zeroLow 2% and fullLow 13% → (0.05-0.02)/(0.13-0.02)*100.
        SleepScoreResult result = calculator.score(sample(30600, 21024, 1440, 6336, 0, 600));

        assertThat(result.deepFraction()).isCloseTo(0.05, within(0.001));
        assertThat(result.deepSubScore()).isCloseTo(27.27, within(0.1));
    }

    @Test
    @DisplayName("REM tolerance: 18% and 27% both score ~80 via the band edges")
    void rem_tolerance_edges() {
        // REM 18% (below full band 20-25): (0.18-0.10)/(0.20-0.10)*100 = 80.
        SleepScoreResult low = calculator.score(sample(30600, 18432, 5184, 5184, 0, 600));
        assertThat(low.remFraction()).isCloseTo(0.18, within(0.001));
        assertThat(low.remSubScore()).isCloseTo(80.0, within(0.1));

        // REM 27% (above full band): (0.35-0.27)/(0.35-0.25)*100 = 80.
        SleepScoreResult high = calculator.score(sample(30600, 15840, 5184, 7776, 0, 600));
        assertThat(high.remFraction()).isCloseTo(0.27, within(0.001));
        assertThat(high.remSubScore()).isCloseTo(80.0, within(0.1));
    }

    @Test
    @DisplayName("a night with no asleep time is not scorable (ERROR), never a 0 score")
    void no_sleep_is_not_scorable() {
        SleepStageSample noSleep = sample(30600, 0, 0, 0, 0, 3600);

        assertThatThrownBy(() -> calculator.score(noSleep))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not scorable");
    }
}
