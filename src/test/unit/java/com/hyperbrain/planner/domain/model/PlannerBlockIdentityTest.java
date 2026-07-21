package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlannerBlockIdentity — stable block identity (#15)")
class PlannerBlockIdentityTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 21);
    private static final UUID EXECUTABLE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_EXECUTABLE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("forBlock is deterministic: the same natural key always derives the same id")
    void for_block_is_deterministic() {
        UUID first = PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 0);
        UUID second = PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 0);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("forBlock separates the id by executable, day and sequence")
    void for_block_separates_by_key_component() {
        UUID base = PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 0);

        assertThat(base)
            .isNotEqualTo(PlannerBlockIdentity.forBlock(OTHER_EXECUTABLE, DAY, 0))
            .isNotEqualTo(PlannerBlockIdentity.forBlock(EXECUTABLE, DAY.plusDays(1), 0))
            .isNotEqualTo(PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 1));
    }

    @Test
    @DisplayName("forBlock rejects null components and a negative sequence")
    void for_block_rejects_invalid_input() {
        assertThatThrownBy(() -> PlannerBlockIdentity.forBlock(null, DAY, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlannerBlockIdentity.forBlock(EXECUTABLE, null, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("assign gives one block per executable the sequence-0 id, stable across a time shift")
    void assign_single_block_keeps_identity_across_time_shift() {
        AgendaBlock morning = block(EXECUTABLE, 9, 10);
        AgendaBlock afternoon = block(EXECUTABLE, 15, 16);

        UUID morningId = PlannerBlockIdentity.assign(List.of(morning), DAY).get(0).blockId();
        UUID afternoonId = PlannerBlockIdentity.assign(List.of(afternoon), DAY).get(0).blockId();

        // The same executable's single block keeps its identity even when the plan moves it to a
        // different hour — this is what lets a replan UPDATE the EKEvent instead of recreating it.
        assertThat(morningId)
            .isEqualTo(afternoonId)
            .isEqualTo(PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 0));
    }

    @Test
    @DisplayName("assign is independent of the incoming list order (chronological sequencing)")
    void assign_is_order_independent() {
        AgendaBlock high = block(OTHER_EXECUTABLE, 8, 9);
        AgendaBlock low = block(EXECUTABLE, 12, 13);

        List<PlannerBlockIdentity.IdentifiedBlock> forward =
            PlannerBlockIdentity.assign(List.of(high, low), DAY);
        List<PlannerBlockIdentity.IdentifiedBlock> reversed =
            PlannerBlockIdentity.assign(List.of(low, high), DAY);

        assertThat(idOf(forward, OTHER_EXECUTABLE)).isEqualTo(idOf(reversed, OTHER_EXECUTABLE));
        assertThat(idOf(forward, EXECUTABLE)).isEqualTo(idOf(reversed, EXECUTABLE));
    }

    @Test
    @DisplayName("assign disambiguates several same-day blocks of one executable by chronological sequence")
    void assign_sequences_multiple_blocks_of_same_executable() {
        AgendaBlock first = block(EXECUTABLE, 9, 10);
        AgendaBlock second = block(EXECUTABLE, 14, 15);

        List<PlannerBlockIdentity.IdentifiedBlock> identified =
            PlannerBlockIdentity.assign(List.of(second, first), DAY);

        // Distinct, deterministic ids: sequence 0 for the earlier block, 1 for the later one, so a
        // future split-per-executable plan never collides on the same PK.
        assertThat(identified).hasSize(2);
        assertThat(idOf(identified, EXECUTABLE, first))
            .isEqualTo(PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 0));
        assertThat(idOf(identified, EXECUTABLE, second))
            .isEqualTo(PlannerBlockIdentity.forBlock(EXECUTABLE, DAY, 1));
        assertThat(identified).extracting(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .doesNotHaveDuplicates();
    }

    private static AgendaBlock block(UUID executableId, int startHour, int endHour) {
        OffsetDateTime start = OffsetDateTime.of(2026, 7, 21, startHour, 0, 0, 0, UTC);
        OffsetDateTime end = OffsetDateTime.of(2026, 7, 21, endHour, 0, 0, 0, UTC);
        return new AgendaBlock(executableId, start, end, false, false, "reason");
    }

    private static UUID idOf(List<PlannerBlockIdentity.IdentifiedBlock> identified, UUID executableId) {
        return identified.stream()
            .filter(entry -> entry.block().executableId().equals(executableId))
            .map(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .findFirst()
            .orElseThrow();
    }

    private static UUID idOf(List<PlannerBlockIdentity.IdentifiedBlock> identified, UUID executableId,
                             AgendaBlock block) {
        return identified.stream()
            .filter(entry -> entry.block().executableId().equals(executableId)
                && entry.block().start().equals(block.start()))
            .map(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .findFirst()
            .orElseThrow();
    }
}
