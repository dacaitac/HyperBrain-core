package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.domain.model.NotionTaskPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.Map;

import static com.hyperbrain.sync.support.ExecutableSnapshotBuilder.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Notion contract of the two Prioritizer-computed columns reflected to Notion (#66a):
 * {@code priority_score} → {@code Priority Score} (normalized {@code [0, 1]}) and the raw
 * {@code urgency_score} → {@code Urgence} (source scale {@code 0–6}).
 *
 * <p>The incident this guards against: a scale or direction drift between the outbound
 * {@link NotionTaskMapper} and the inbound {@link NotionTaskInboundMapper} would silently corrupt
 * every mirrored score (e.g. normalizing urgency to {@code [0, 1]} outbound, or clamping priority to
 * the wrong range inbound). These tests fail the moment the outbound → inbound round-trip stops being
 * the identity over each column's documented range, or the number lands in the wrong Notion property.
 */
@DisplayName("Notion computed-score contract — Priority Score [0,1] + raw Urgence [0,6] round-trip")
class NotionComputedScoreContractTest {

    @Nested
    @DisplayName("Priority Score — normalized [0, 1], written to the 'Priority Score' number property")
    class PriorityScoreColumn {

        @ParameterizedTest(name = "priority {0} survives the outbound → inbound round-trip unchanged")
        @ValueSource(doubles = {0.0, 0.25, 0.5, 0.77, 1.0})
        void priority_round_trips(double value) {
            Map<String, Object> props =
                NotionTaskMapper.map(snapshot().priorityScore(value).build(), null, null);

            assertThat(numberOf(props, "Priority Score")).isEqualTo(value);

            NotionTaskPage echoed = pageWithNumbers(value, null);
            ExecutableSnapshot decoded =
                NotionTaskInboundMapper.toSnapshot(echoed, ID(), USER(), null, null);
            assertThat(decoded.priorityScore()).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Urgence — RAW 0–6 (never normalized), written to the 'Urgence' number property")
    class UrgenceColumn {

        @ParameterizedTest(name = "raw urgency {0} survives the outbound → inbound round-trip unchanged")
        @ValueSource(doubles = {0.0, 1.5, 3.0, 5.0, 6.0})
        void urgency_round_trips_raw(double value) {
            Map<String, Object> props =
                NotionTaskMapper.map(snapshot().urgencyScore(value).build(), null, null);

            // reflected raw on the 0-6 source scale, NOT normalized to [0, 1]
            assertThat(numberOf(props, "Urgence")).isEqualTo(value);

            NotionTaskPage echoed = pageWithNumbers(null, value);
            ExecutableSnapshot decoded =
                NotionTaskInboundMapper.toSnapshot(echoed, ID(), USER(), null, null);
            assertThat(decoded.urgencyScore()).isEqualTo(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Double numberOf(Map<String, Object> props, String property) {
        Map<String, Object> number = (Map<String, Object>) props.get(property);
        Object value = number.get("number");
        return value != null ? ((Number) value).doubleValue() : null;
    }

    private static NotionTaskPage pageWithNumbers(Double priority, Double urgency) {
        return new NotionTaskPage("page0000000000000000000000000001",
            OffsetDateTime.parse("2026-07-07T15:00:00Z"), false,
            "t", null, "Not started", false, null,
            null, null, priority, urgency, null, null, null,
            null, null, null, null, null);
    }

    private static java.util.UUID ID() {
        return java.util.UUID.fromString("22222222-2222-2222-2222-222222222222");
    }

    private static java.util.UUID USER() {
        return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
}
