package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WindowSize — the three sizes and their internal split (ADR-040)")
class WindowSizeTest {

    @Test
    @DisplayName("each sanctioned duration classifies into its own size")
    void sanctioned_durations_classify_exactly() {
        assertThat(WindowSize.of(30)).isEqualTo(WindowSize.SHORT);
        assertThat(WindowSize.of(60)).isEqualTo(WindowSize.STANDARD);
        assertThat(WindowSize.of(120)).isEqualTo(WindowSize.DEEP);
    }

    @Test
    @DisplayName("an off-template duration degrades to the largest size it fully contains")
    void an_odd_duration_degrades_downwards() {
        // A 106-minute whirlwind band is not a multiple of anything; the template stays soft, so it
        // reports the largest size it contains rather than being rejected.
        assertThat(WindowSize.of(106)).isEqualTo(WindowSize.STANDARD);
        assertThat(WindowSize.of(45)).isEqualTo(WindowSize.SHORT);
        assertThat(WindowSize.of(20)).isEqualTo(WindowSize.UNSIZED);
    }

    @Test
    @DisplayName("every sized window carries a readable split; the unsized one carries none")
    void the_split_is_present_exactly_for_the_sized_windows() {
        assertThat(WindowSize.SHORT.internalSplit()).isEqualTo("25 + 5");
        assertThat(WindowSize.STANDARD.internalSplit()).contains("50+10");
        assertThat(WindowSize.DEEP.internalSplit()).contains("90+30");
        assertThat(WindowSize.UNSIZED.internalSplit()).isNull();
    }
}
