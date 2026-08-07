package com.hyperbrain.core.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OverduePolicy — the ADR-040 D4 dispatch table")
class OverduePolicyTest {

    @Test
    @DisplayName("the four types that leave the inventory as a sanctioned miss")
    void types_closed_as_failed() {
        assertThat(OverduePolicy.actionFor("HABIT")).isEqualTo(OverdueAction.CLOSE_AS_FAILED);
        assertThat(OverduePolicy.actionFor("LEAD_MEASURE")).isEqualTo(OverdueAction.CLOSE_AS_FAILED);
        assertThat(OverduePolicy.actionFor("ACTIVITY")).isEqualTo(OverdueAction.CLOSE_AS_FAILED);
        assertThat(OverduePolicy.actionFor("AGENDA")).isEqualTo(OverdueAction.CLOSE_AS_FAILED);
    }

    @Test
    @DisplayName("TASK is the only type that moves instead of closing")
    void task_is_rescheduled() {
        assertThat(OverduePolicy.actionFor("TASK")).isEqualTo(OverdueAction.RESCHEDULE);
    }

    @Test
    @DisplayName("BUYING returns to the dateless bag")
    void buying_loses_its_date() {
        assertThat(OverduePolicy.actionFor("BUYING")).isEqualTo(OverdueAction.CLEAR_DATE);
    }

    @Test
    @DisplayName("TIME_BLOCK is not swept here: its window closes intraday, settled by the block sweep")
    void time_block_is_not_swept_by_the_day_close() {
        assertThat(OverduePolicy.actionFor("TIME_BLOCK")).isEqualTo(OverdueAction.NONE);
        assertThat(OverduePolicy.sweptTypes()).doesNotContain("TIME_BLOCK");
    }

    @Test
    @DisplayName("LEARNING_SESSION has no rule in the table and is left alone; so is an unknown or null type")
    void unruled_types_are_left_alone() {
        assertThat(OverduePolicy.actionFor("LEARNING_SESSION")).isEqualTo(OverdueAction.NONE);
        assertThat(OverduePolicy.actionFor("SOMETHING_ELSE")).isEqualTo(OverdueAction.NONE);
        assertThat(OverduePolicy.actionFor(null)).isEqualTo(OverdueAction.NONE);
    }

    @Test
    @DisplayName("the swept-type set is exactly the types with an action, so the SQL filter cannot drift from the table")
    void swept_types_match_the_table() {
        assertThat(OverduePolicy.sweptTypes())
            .containsExactlyInAnyOrder("HABIT", "LEAD_MEASURE", "ACTIVITY", "AGENDA", "TASK", "BUYING");
        assertThat(OverduePolicy.sweptTypes())
            .allSatisfy(type -> assertThat(OverduePolicy.actionFor(type)).isNotEqualTo(OverdueAction.NONE));
    }
}
