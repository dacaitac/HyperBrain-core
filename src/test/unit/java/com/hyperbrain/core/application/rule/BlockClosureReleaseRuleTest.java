package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.application.OverdueSweepService;
import com.hyperbrain.core.domain.model.OverdueSweepReport;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("BlockClosureReleaseRule — closing a block lets the work it held go")
class BlockClosureReleaseRuleTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLOCK = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private OverdueSweepService sweepService;
    private BlockClosureReleaseRule rule;

    @BeforeEach
    void setUp() {
        sweepService = mock(OverdueSweepService.class);
        ExecutableStateRepository stateRepo = mock(ExecutableStateRepository.class);
        when(stateRepo.findUserZone(USER)).thenReturn(ZONE);
        when(sweepService.releaseMembersOf(any(), any(), any()))
            .thenReturn(new OverdueSweepReport(0, 0, 0));
        rule = new BlockClosureReleaseRule(sweepService, stateRepo);
    }

    @Test
    @DisplayName("a block ticked by hand in Notion releases its members")
    void a_block_closed_by_hand_releases_its_members() {
        ExecutableSnapshot result = rule.apply(
            block("PLANNED"), block("DONE"), ExternalSystem.NOTION);

        verify(sweepService).releaseMembersOf(eq(BLOCK), any(), eq(ZONE));
        // The rule reports the state it received: it lets work go, it does not rewrite the block.
        assertThat(result.status()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("re-ingesting an already-closed block releases nothing: the echo of our own write-back")
    void an_echo_of_our_own_write_back_releases_nothing() {
        rule.apply(block("DONE"), block("DONE"), ExternalSystem.NOTION);

        verifyNoInteractions(sweepService);
    }

    @Test
    @DisplayName("only blocks trigger it, and never a row born closed on CREATE")
    void other_types_and_creations_are_left_alone() {
        ExecutableSnapshot previousTask = ExecutableSnapshotBuilder.snapshot()
            .id(BLOCK).userId(USER).type("TASK").status("TODO").build();
        ExecutableSnapshot closedTask = ExecutableSnapshotBuilder.snapshot()
            .id(BLOCK).userId(USER).type("TASK").status("DONE").build();

        rule.apply(previousTask, closedTask, ExternalSystem.NOTION);
        // A CREATE has no previous state, so it carries no observable transition.
        rule.apply(null, block("DONE"), ExternalSystem.APPLE);

        verifyNoInteractions(sweepService);
    }

    private static ExecutableSnapshot block(String status) {
        return ExecutableSnapshotBuilder.snapshot()
            .id(BLOCK).userId(USER).type("TIME_BLOCK").status(status).build();
    }
}
