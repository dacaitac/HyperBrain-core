package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContainmentEligibilityRule (ADR-039: only reminder types can be contained)")
class ContainmentEligibilityRuleTest {

    private static final UUID BLOCK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final UUID CYCLE_ID = UUID.fromString("cccccccc-0000-0000-0000-0000000000e1");

    private ContainmentEligibilityRule rule;

    @BeforeEach
    void setUp() {
        rule = new ContainmentEligibilityRule();
    }

    @ParameterizedTest(name = "{0} cannot be contained → container cleared, window kept")
    @ValueSource(strings = {"ACTIVITY", "LEARNING_SESSION", "AGENDA"})
    @DisplayName("a calendar-event / AGENDA type dropped into a block is NOT contained (container cleared)")
    void calendar_types_are_never_contained(String type) {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type(type).status("TODO").cycleId(CYCLE_ID).containerBlockId(BLOCK_ID)
            .build();

        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        assertThat(result.containerBlockId()).isNull();
        // The type keeps its own schedule/cycle; only the containment link is cleared.
        assertThat(result.cycleId()).isEqualTo(CYCLE_ID);
        assertThat(result.type()).isEqualTo(type);
    }

    @ParameterizedTest(name = "{0} may be contained → container preserved")
    @ValueSource(strings = {"TASK", "HABIT", "LEAD_MEASURE", "BUYING"})
    @DisplayName("a reminder-backed type keeps its containment")
    void reminder_types_keep_containment(String type) {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type(type).status("TODO").containerBlockId(BLOCK_ID)
            .build();

        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(merged);
        assertThat(result.containerBlockId()).isEqualTo(BLOCK_ID);
    }

    @Test
    @DisplayName("an uncontained calendar event is untouched (no-op)")
    void uncontained_calendar_event_is_noop() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .type("ACTIVITY").status("TODO").build();

        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.APPLE);

        assertThat(result).isSameAs(merged);
        assertThat(result.containerBlockId()).isNull();
    }
}
