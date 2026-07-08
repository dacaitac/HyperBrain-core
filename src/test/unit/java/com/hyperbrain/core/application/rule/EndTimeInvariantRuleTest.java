package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EndTimeInvariantRule (DR-01)")
class EndTimeInvariantRuleTest {

    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 7, 8, 14, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END =
        OffsetDateTime.of(2026, 7, 8, 15, 0, 0, 0, ZoneOffset.UTC);

    private final EndTimeInvariantRule rule = new EndTimeInvariantRule();

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"ACTIVITY", "AGENDA"})
    @DisplayName("preserves end_time for fixed time-block types")
    void preserves_end_time_for_time_block_types(String type) {
        ExecutableSnapshot result =
            rule.apply(null, snapshot(type, START, END), ExternalSystem.APPLE);

        assertThat(result.endTime()).isEqualTo(END);
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"TASK", "HABIT", "LEAD_MEASURE", "LEARNING_SESSION"})
    @DisplayName("clears end_time for non-time-block types")
    void clears_end_time_for_non_time_block_types(String type) {
        ExecutableSnapshot result =
            rule.apply(null, snapshot(type, START, END), ExternalSystem.APPLE);

        assertThat(result.endTime()).isNull();
    }

    @Test
    @DisplayName("preserves all other fields when clearing end_time")
    void preserves_other_fields_when_clearing_end_time() {
        ExecutableSnapshot input = snapshot("TASK", START, END);

        ExecutableSnapshot result = rule.apply(null, input, ExternalSystem.APPLE);

        assertThat(result)
            .usingRecursiveComparison()
            .ignoringFields("endTime")
            .isEqualTo(input);
        assertThat(result.endTime()).isNull();
    }

    @Test
    @DisplayName("is a no-op when end_time is already null")
    void noop_when_end_time_null() {
        ExecutableSnapshot input = snapshot("TASK", START, null);

        ExecutableSnapshot result = rule.apply(null, input, ExternalSystem.APPLE);

        assertThat(result).isSameAs(input);
    }

    private static ExecutableSnapshot snapshot(String type, OffsetDateTime start, OffsetDateTime end) {
        return ExecutableSnapshotBuilder.snapshot()
            .type(type).startTime(start).endTime(end)
            .build();
    }
}
