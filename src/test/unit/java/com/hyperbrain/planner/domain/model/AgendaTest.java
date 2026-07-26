package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Agenda — daily uniqueness invariant (ADR-027 D5): ≤ 1 block per executable per day")
class AgendaTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 21, 9, 0, 0, 0, UTC);
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @DisplayName("a day whose blocks reference distinct executables is accepted")
    void distinct_membership_is_accepted() {
        Agenda agenda = new Agenda(
            List.of(
                block(A, WAKE, WAKE.plusMinutes(60)),
                block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            List.of(), List.of(), "criterion", false);

        assertThat(agenda.blocks()).hasSize(2);
    }

    @Test
    @DisplayName("an executable anchoring two blocks the same day is rejected")
    void same_anchor_in_two_blocks_is_rejected() {
        assertThatThrownBy(() -> new Agenda(
            List.of(
                block(A, WAKE, WAKE.plusMinutes(60)),
                block(A, WAKE.plusMinutes(90), WAKE.plusMinutes(150))),
            List.of(), List.of(), "criterion", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most one block per day")
            .hasMessageContaining(A.toString());
    }

    @Test
    @DisplayName("an executable that anchors one block and joins another as a companion is rejected")
    void executable_shared_between_blocks_as_member_is_rejected() {
        AgendaBlock themed = new AgendaBlock(
            B, WAKE.plusMinutes(90), WAKE.plusMinutes(150), false, false, "themed", List.of(A));

        // A anchors the first block AND is a companion of the themed second block → two blocks, same day.
        assertThatThrownBy(() -> new Agenda(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), themed),
            List.of(), List.of(), "criterion", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ADR-027 D5")
            .hasMessageContaining(A.toString());
    }

    @Test
    @DisplayName("distinct themed blocks with no shared members are accepted")
    void distinct_themed_blocks_are_accepted() {
        AgendaBlock first = new AgendaBlock(
            A, WAKE, WAKE.plusMinutes(60), false, false, "theme one", List.of(B));
        AgendaBlock second = new AgendaBlock(
            C, WAKE.plusMinutes(90), WAKE.plusMinutes(150), false, false, "theme two");

        Agenda agenda = new Agenda(List.of(first, second), List.of(), List.of(), "criterion", false);

        assertThat(agenda.blocks()).hasSize(2);
    }

    private static AgendaBlock block(UUID id, OffsetDateTime start, OffsetDateTime end) {
        return new AgendaBlock(id, start, end, false, false, "reason");
    }
}
