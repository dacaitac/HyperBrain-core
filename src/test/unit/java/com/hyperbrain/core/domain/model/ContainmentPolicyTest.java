package com.hyperbrain.core.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ContainmentPolicy — the two containment invariants as pure functions (ADR-040 D11)")
class ContainmentPolicyTest {

    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 7, 8, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END =
        OffsetDateTime.of(2026, 8, 7, 10, 30, 0, 0, ZoneOffset.UTC);
    private static final UUID CONTAINER_CYCLE = UUID.randomUUID();
    private static final UUID CHILD_CYCLE = UUID.randomUUID();

    @Test
    @DisplayName("only the reminder-backed types may live inside a block")
    void reminder_types_are_containable() {
        assertThat(ContainmentPolicy.containable("TASK")).isTrue();
        assertThat(ContainmentPolicy.containable("HABIT")).isTrue();
        assertThat(ContainmentPolicy.containable("LEAD_MEASURE")).isTrue();
        assertThat(ContainmentPolicy.containable("BUYING")).isTrue();
    }

    @Test
    @DisplayName("a type that already owns a calendar window can never be contained")
    void calendar_types_are_not_containable() {
        assertThat(ContainmentPolicy.containable("ACTIVITY")).isFalse();
        assertThat(ContainmentPolicy.containable("LEARNING_SESSION")).isFalse();
        assertThat(ContainmentPolicy.containable("AGENDA")).isFalse();
    }

    @Test
    @DisplayName("a null type is never containable")
    void a_null_type_is_not_containable() {
        assertThat(ContainmentPolicy.containable(null)).isFalse();
    }

    @Test
    @DisplayName("a reminder-backed child receives the container's start only — never an end (DR-01)")
    void a_reminder_child_receives_no_end_instant() {
        // Given
        ContainerSchedule container = container(CONTAINER_CYCLE);

        // When
        ContainedSchedule asserted =
            ContainmentPolicy.assertedSchedule(container, "TASK", CHILD_CYCLE);

        // Then
        assertThat(asserted.startTime()).isEqualTo(START);
        assertThat(asserted.endTime()).isNull();
        assertThat(asserted.cycleId()).isEqualTo(CONTAINER_CYCLE);
    }

    @Test
    @DisplayName("an event-backed child receives the container's whole window")
    void an_event_child_receives_the_full_window() {
        // When
        ContainedSchedule asserted =
            ContainmentPolicy.assertedSchedule(container(CONTAINER_CYCLE), "ACTIVITY", CHILD_CYCLE);

        // Then
        assertThat(asserted.startTime()).isEqualTo(START);
        assertThat(asserted.endTime()).isEqualTo(END);
    }

    @Test
    @DisplayName("a container with no cycle carries no signal: the child keeps its own")
    void a_null_container_cycle_preserves_the_child_cycle() {
        // When
        ContainedSchedule asserted =
            ContainmentPolicy.assertedSchedule(container(null), "TASK", CHILD_CYCLE);

        // Then
        assertThat(asserted.cycleId()).isEqualTo(CHILD_CYCLE);
    }

    @Test
    @DisplayName("the policy refuses to derive a schedule from nothing")
    void null_arguments_are_rejected() {
        assertThatThrownBy(() -> ContainmentPolicy.assertedSchedule(null, "TASK", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            ContainmentPolicy.assertedSchedule(container(null), null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ContainerSchedule container(UUID cycleId) {
        return new ContainerSchedule(UUID.randomUUID(), "Deep work", START, END, cycleId);
    }
}
