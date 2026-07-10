package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.hyperbrain.sync.support.ExecutableSnapshotBuilder.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Notion scale-select contract so the positional {@code indexOf + 1} encoding shared by
 * the outbound {@link NotionTaskMapper} and inbound {@link NotionTaskInboundMapper} cannot drift.
 *
 * <p>The incident this guards against: the encoding is positional, so adding, removing or
 * reordering a select option silently shifts every domain value ↔ option pairing. These tests fail
 * the moment the option lists change shape, or the outbound → inbound round-trip stops being the
 * identity over the domain range 1–5.
 */
@DisplayName("Notion scale contract — canonical option lists + outbound/inbound round-trip")
class NotionScaleContractTest {

    @Nested
    @DisplayName("canonical option lists (exact names, order and size)")
    class OptionLists {

        @Test
        @DisplayName("IMPACT_OPTIONS is the 5-level Notion select in order")
        void impact_options_are_pinned() {
            assertThat(NotionSchema.IMPACT_OPTIONS).containsExactly(
                "Irrelevant", "Low", "Moderate", "High", "Critical");
        }

        @Test
        @DisplayName("ENERGY_OPTIONS is the 5-level Notion select in order")
        void energy_options_are_pinned() {
            assertThat(NotionSchema.ENERGY_OPTIONS).containsExactly(
                "Automatic", "Execution", "Sustained", "Demanding", "Intense");
        }

        @Test
        @DisplayName("MENTAL_LOAD_OPTIONS is the 5-level Notion select in order")
        void mental_load_options_are_pinned() {
            assertThat(NotionSchema.MENTAL_LOAD_OPTIONS).containsExactly(
                "Routine", "Focus", "Analysis", "Complex", "Abstract");
        }
    }

    @Nested
    @DisplayName("outbound ↔ inbound round-trip is the identity over 1..5")
    class RoundTrip {

        @ParameterizedTest(name = "impact {0} survives the round-trip")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void impact_round_trips(int value) {
            assertRoundTrip(value,
                snapshot -> snapshot.impact(value), "Impact", NotionSchema.IMPACT_OPTIONS);
        }

        @ParameterizedTest(name = "energy {0} survives the round-trip")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void energy_round_trips(int value) {
            assertRoundTrip(value,
                snapshot -> snapshot.energyDrain(value), "Energy", NotionSchema.ENERGY_OPTIONS);
        }

        @ParameterizedTest(name = "mental load {0} survives the round-trip")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void mental_load_round_trips(int value) {
            assertRoundTrip(value,
                snapshot -> snapshot.mentalLoad(value), "Mental Load", NotionSchema.MENTAL_LOAD_OPTIONS);
        }

        /**
         * Encodes {@code value} into a Notion select via the outbound mapper, then decodes it back
         * with the inbound {@code scaleOf}, asserting the outbound option name and the decoded
         * domain value both match — the positional encoding is a lossless identity over 1..5.
         */
        private void assertRoundTrip(int value,
                                     Function<ExecutableSnapshotBuilder, ExecutableSnapshotBuilder> withScale,
                                     String property, List<String> options) {
            Map<String, Object> props =
                NotionTaskMapper.map(withScale.apply(snapshot()).build(), null, null);

            String optionName = selectName(props, property);
            assertThat(optionName).isEqualTo(options.get(value - 1));
            assertThat(NotionTaskInboundMapper.scaleOf(optionName, options)).isEqualTo(value);
        }

        @SuppressWarnings("unchecked")
        private String selectName(Map<String, Object> props, String property) {
            Map<String, Object> select = (Map<String, Object>) props.get(property);
            return ((Map<String, String>) select.get("select")).get("name");
        }
    }
}
