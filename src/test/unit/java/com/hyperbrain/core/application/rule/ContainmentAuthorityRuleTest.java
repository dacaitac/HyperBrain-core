package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ContainmentAuthorityRule — a membership he assigns by hand makes the block his")
class ContainmentAuthorityRuleTest {

    private static final UUID TASK = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID BLOCK = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_BLOCK = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b2");

    private ExecutableStateRepository stateRepo;
    private ContainmentAuthorityRule rule;

    @BeforeEach
    void setUp() {
        stateRepo = mock(ExecutableStateRepository.class);
        rule = new ContainmentAuthorityRule(stateRepo);
    }

    @Test
    @DisplayName("dragging a task into a block in Notion hands that block over to the user")
    void a_hand_placement_hands_the_block_over() {
        when(stateRepo.claimBlockForUser(BLOCK)).thenReturn(true);

        ExecutableSnapshot result = rule.apply(task(null), task(BLOCK), ExternalSystem.NOTION);

        verify(stateRepo).claimBlockForUser(BLOCK);
        // The rule decides about the block, never about the row passing through it.
        assertThat(result).usingRecursiveComparison().isEqualTo(task(BLOCK));
    }

    @Test
    @DisplayName("moving it from one block to another hands over the block that received it")
    void a_move_hands_over_the_receiving_block() {
        rule.apply(task(BLOCK), task(OTHER_BLOCK), ExternalSystem.NOTION);

        verify(stateRepo).claimBlockForUser(OTHER_BLOCK);
    }

    @Test
    @DisplayName("the other surface he edits counts the same: a hand placement in Apple hands over too")
    void a_hand_placement_from_apple_hands_the_block_over() {
        // Notion is where the drag happens today, but the rule turns on «a person did this», not on
        // which of the two surfaces he was holding — and Apple is the other one.
        rule.apply(task(null), task(BLOCK), ExternalSystem.APPLE);

        verify(stateRepo).claimBlockForUser(BLOCK);
    }

    @Test
    @DisplayName("an origin nobody recognises hands nothing over: authority is granted, never assumed")
    void an_unknown_origin_hands_nothing_over() {
        // A malformed source_system parses to UNKNOWN rather than failing, so it does reach the rules.
        // Handing a block over on it would give away the plan on a corrupt header.
        rule.apply(task(null), task(BLOCK), ExternalSystem.UNKNOWN);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("our own core writing through the pipeline hands nothing over either")
    void a_core_origin_hands_nothing_over() {
        rule.apply(task(null), task(BLOCK), ExternalSystem.HYPERBRAIN_CORE);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("a block that was not the planner's to give leaves the row passing through untouched")
    void a_refused_claim_still_returns_the_merged_row() {
        // The repository is the one guard that knows the block's state; refusing (the unstubbed
        // default here) means it was already the user's, already under way or already closed — and the
        // rule has nothing to say about the row either way.
        ExecutableSnapshot result = rule.apply(task(null), task(BLOCK), ExternalSystem.NOTION);

        assertThat(result).usingRecursiveComparison().isEqualTo(task(BLOCK));
    }

    @Test
    @DisplayName("a task created already inside a block counts as a hand placement")
    void a_creation_inside_a_block_hands_it_over() {
        rule.apply(null, task(BLOCK), ExternalSystem.NOTION);

        verify(stateRepo).claimBlockForUser(BLOCK);
    }

    @Test
    @DisplayName("the echo of the planner's own containment hands nothing over")
    void an_unchanged_containment_hands_nothing_over() {
        rule.apply(task(BLOCK), task(BLOCK), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("the planner writing its own plan hands nothing over")
    void a_system_containment_hands_nothing_over() {
        rule.apply(task(null), task(BLOCK), ExternalSystem.SYSTEM);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("taking a task out of a block hands nothing over: there is no window left to anchor to")
    void a_release_hands_nothing_over() {
        rule.apply(task(BLOCK), task(null), ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    @Test
    @DisplayName("a system-generated row carries no intention of the user's")
    void a_system_generated_row_hands_nothing_over() {
        ExecutableSnapshot snapshot = ExecutableSnapshotBuilder.snapshot()
            .id(TASK).type("TASK").status("TODO").containerBlockId(BLOCK).systemGenerated(true).build();

        rule.apply(null, snapshot, ExternalSystem.NOTION);

        verifyNoInteractions(stateRepo);
    }

    private static ExecutableSnapshot task(UUID containerBlockId) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(TASK).type("TASK").status("TODO").containerBlockId(containerBlockId).build();
    }
}
