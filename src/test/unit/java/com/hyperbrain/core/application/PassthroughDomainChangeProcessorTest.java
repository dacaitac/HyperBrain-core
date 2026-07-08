package com.hyperbrain.core.application;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PassthroughDomainChangeProcessor")
class PassthroughDomainChangeProcessorTest {

    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 7, 8, 14, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END =
        OffsetDateTime.of(2026, 7, 8, 15, 0, 0, 0, ZoneOffset.UTC);

    private final PassthroughDomainChangeProcessor processor =
        new PassthroughDomainChangeProcessor();

    @Nested
    @DisplayName("end_time invariant: only ACTIVITY and AGENDA may carry an end_time")
    class EndTimeInvariant {

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"ACTIVITY", "AGENDA"})
        @DisplayName("preserves end_time for fixed time-block types")
        void preserves_end_time_for_time_block_types(String type) {
            ExecutableSnapshot result = processor.process(snapshot(type, START, END), ExternalSystem.APPLE);

            assertThat(result.endTime()).isEqualTo(END);
        }

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"TASK", "HABIT", "LEAD_MEASURE", "LEARNING_SESSION"})
        @DisplayName("clears end_time for non-time-block types")
        void clears_end_time_for_non_time_block_types(String type) {
            ExecutableSnapshot result = processor.process(snapshot(type, START, END), ExternalSystem.APPLE);

            assertThat(result.endTime()).isNull();
        }

        @Test
        @DisplayName("preserves all other fields when clearing end_time")
        void preserves_other_fields_when_clearing_end_time() {
            ExecutableSnapshot input = snapshot("TASK", START, END);

            ExecutableSnapshot result = processor.process(input, ExternalSystem.APPLE);

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

            ExecutableSnapshot result = processor.process(input, ExternalSystem.APPLE);

            assertThat(result).isSameAs(input);
        }
    }

    private static ExecutableSnapshot snapshot(String type, OffsetDateTime start, OffsetDateTime end) {
        return new ExecutableSnapshot(
            UUID.randomUUID(), UUID.randomUUID(), null, null,
            "Test task", null, type, "TODO",
            null, null, null, false, null,
            start, end, null,
            null, null, null);
    }
}
