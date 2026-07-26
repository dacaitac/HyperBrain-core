package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgendaBlock — themed time container with N members (ADR-027 D1/D2/D4)")
class AgendaBlockTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final UUID ANCHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID THIRD = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 7, 21, 9, 0, 0, 0, UTC);

    @Test
    @DisplayName("a single-executable block holds just its anchor")
    void single_executable_block_holds_only_its_anchor() {
        AgendaBlock block = new AgendaBlock(ANCHOR, START, START.plusMinutes(60), false, false, "why");

        assertThat(block.additionalMembers()).isEmpty();
        assertThat(block.members()).containsExactly(ANCHOR);
        assertThat(block.memberPlannedMinutes()).containsExactly(60);
    }

    @Test
    @DisplayName("members list the anchor first, then the theme's other executables in order")
    void members_are_anchor_first() {
        AgendaBlock block = themed(60, List.of(SECOND, THIRD));

        assertThat(block.members()).containsExactly(ANCHOR, SECOND, THIRD);
    }

    @Test
    @DisplayName("the container's duration is split evenly across its members")
    void planned_minutes_split_evenly() {
        assertThat(themed(90, List.of(SECOND, THIRD)).memberPlannedMinutes())
            .containsExactly(30, 30, 30);
    }

    @Test
    @DisplayName("an indivisible duration gives the remainder to the leading members, never losing a minute")
    void planned_minutes_split_keeps_the_total() {
        AgendaBlock block = themed(50, List.of(SECOND, THIRD));

        assertThat(block.memberPlannedMinutes()).containsExactly(17, 17, 16);
        assertThat(block.memberPlannedMinutes().stream().mapToInt(Integer::intValue).sum())
            .isEqualTo((int) block.durationMinutes());
    }

    @Test
    @DisplayName("a WIG block is atomic: it never groups other executables")
    void wig_block_is_atomic() {
        assertThatThrownBy(() -> new AgendaBlock(ANCHOR, START, START.plusMinutes(60), true, false,
            "WIG lead measure", List.of(SECOND)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("atomic");
    }

    @Test
    @DisplayName("membership rejects a repeated anchor, duplicates and nulls")
    void membership_rejects_invalid_input() {
        assertThatThrownBy(() -> themed(60, List.of(ANCHOR)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> themed(60, List.of(SECOND, SECOND)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> themed(60, java.util.Collections.singletonList(null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the membership view is immutable and defensively copied")
    void membership_is_immutable() {
        List<UUID> mutable = new java.util.ArrayList<>(List.of(SECOND));
        AgendaBlock block = themed(60, mutable);
        mutable.add(THIRD);

        assertThat(block.additionalMembers()).containsExactly(SECOND);
        assertThatThrownBy(() -> block.members().add(THIRD))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AgendaBlock themed(int minutes, List<UUID> additionalMembers) {
        return new AgendaBlock(ANCHOR, START, START.plusMinutes(minutes), false, false, "why",
            additionalMembers);
    }
}
