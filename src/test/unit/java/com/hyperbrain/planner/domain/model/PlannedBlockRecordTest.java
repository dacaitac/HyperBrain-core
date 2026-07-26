package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlannedBlockRecord — persisted themed container re-read for the write-back (ADR-027 D1)")
class PlannedBlockRecordTest {

    private static final UUID BLOCK = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID ANCHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = START.plusHours(2);

    @Test
    @DisplayName("the theme titles the calendar event when the LLM named the container")
    void theme_titles_the_event() {
        assertThat(record("Deep work morning", members()).title()).isEqualTo("Deep work morning");
    }

    @Test
    @DisplayName("a themeless block (deterministic floor) falls back to the anchor's name")
    void themeless_block_falls_back_to_the_anchor_name() {
        // The floor leaves the theme null, so the calendar reads exactly as it did before grouping.
        assertThat(record(null, members()).title()).isEqualTo("Write the report");
        assertThat(record("   ", members()).title()).isEqualTo("Write the report");
    }

    @Test
    @DisplayName("the anchor is the first member by ord, and grouped() reports real grouping only")
    void anchor_and_grouping() {
        PlannedBlockRecord single = record(null, List.of(member(ANCHOR, "Write the report", 120, 0)));
        PlannedBlockRecord grouped = record("Deep work", members());

        assertThat(single.anchor().executableId()).isEqualTo(ANCHOR);
        assertThat(single.grouped()).isFalse();
        assertThat(grouped.anchor().executableId()).isEqualTo(ANCHOR);
        assertThat(grouped.grouped()).isTrue();
    }

    @Test
    @DisplayName("a record without members is rejected: a container always holds work")
    void empty_membership_is_rejected() {
        assertThatThrownBy(() -> record(null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannedBlockRecord(BLOCK, null, null, START, END, "why"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the record rejects a null id and a non-positive window")
    void invalid_coordinates_are_rejected() {
        assertThatThrownBy(() -> new PlannedBlockRecord(null, null, members(), START, END, "why"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannedBlockRecord(BLOCK, null, members(), END, START, "why"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the membership view is immutable")
    void membership_is_immutable() {
        PlannedBlockRecord block = record("Deep work", members());

        assertThatThrownBy(() -> block.members().add(member(COMPANION, "Another", 10, 2)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a member rejects a blank name and negative minutes or ord")
    void member_rejects_invalid_input() {
        assertThatThrownBy(() -> new PlannedBlockMember(ANCHOR, " ", 10, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannedBlockMember(ANCHOR, "Name", -1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannedBlockMember(ANCHOR, "Name", 10, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannedBlockMember(null, "Name", 10, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<PlannedBlockMember> members() {
        return List.of(
            member(ANCHOR, "Write the report", 60, 0),
            member(COMPANION, "Review PRs", 60, 1));
    }

    private static PlannedBlockMember member(UUID id, String name, int minutes, int ord) {
        return new PlannedBlockMember(id, name, minutes, ord);
    }

    private static PlannedBlockRecord record(String theme, List<PlannedBlockMember> members) {
        return new PlannedBlockRecord(BLOCK, theme, members, START, END, "Ranked by priority");
    }
}
