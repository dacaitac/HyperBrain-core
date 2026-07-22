package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.application.AgendaPropuesta.BlockDecision;
import com.hyperbrain.cognitive.application.AgendaPropuesta.Placement;
import com.hyperbrain.cognitive.application.ProposalWallGuard.ProposalWall;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallGuardResult;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProposalWallGuard — bounded hard-wall guard, all-or-nothing (H3 authority model)")
class ProposalWallGuardTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ProposalWallGuard guard = new ProposalWallGuard();

    @Test
    @DisplayName("a proposal that keeps every block within the walls passes clean")
    void clean_keep_passes() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            Set.of(), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(keep(A), keep(B)));

        WallGuardResult result = guard.check(propuesta, context);

        assertThat(result.clean()).isTrue();
    }

    @Test
    @DisplayName("an ACTIVITY moved within the frontier is accepted (LLM arrangement authority)")
    void moved_activity_within_frontier_passes() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, WAKE.plusMinutes(300), WAKE.plusMinutes(360))));

        assertThat(guard.check(propuesta, context).clean()).isTrue();
    }

    @Test
    @DisplayName("dropping a non-WIG block is allowed")
    void drop_non_wig_passes() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            Set.of(A), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(keep(A), drop(B)));

        assertThat(guard.check(propuesta, context).clean()).isTrue();
    }

    @Test
    @DisplayName("STRUCTURAL_IDENTITY: an invented block id is rejected (anti-hallucination)")
    void invented_block_rejected() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(keep(A), keep(B))); // B is not in the run

        WallGuardResult result = guard.check(propuesta, context);

        assertThat(result.clean()).isFalse();
        assertThat(result.breaches())
            .anySatisfy(w -> {
                assertThat(w.blockId()).isEqualTo(B);
                assertThat(w.wall()).isEqualTo(ProposalWall.STRUCTURAL_IDENTITY);
            });
    }

    @Test
    @DisplayName("STRUCTURAL_IDENTITY: a silently dropped block (not covered) is rejected")
    void uncovered_block_rejected() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            Set.of(), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(keep(A))); // B missing entirely

        WallGuardResult result = guard.check(propuesta, context);

        assertThat(result.breaches())
            .anySatisfy(w -> {
                assertThat(w.blockId()).isEqualTo(B);
                assertThat(w.wall()).isEqualTo(ProposalWall.STRUCTURAL_IDENTITY);
            });
    }

    @Test
    @DisplayName("STRUCTURAL_IDENTITY: a duplicated block id is rejected")
    void duplicate_block_rejected() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(keep(A), keep(A)));

        assertThat(guard.check(propuesta, context).breaches())
            .anySatisfy(w -> assertThat(w.wall()).isEqualTo(ProposalWall.STRUCTURAL_IDENTITY));
    }

    @Test
    @DisplayName("WIG_PROTECTED: dropping the WIG block is rejected")
    void wig_drop_rejected() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(A), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(drop(A)));

        WallGuardResult result = guard.check(propuesta, context);

        assertThat(result.breaches()).singleElement()
            .satisfies(w -> {
                assertThat(w.blockId()).isEqualTo(A);
                assertThat(w.wall()).isEqualTo(ProposalWall.WIG_PROTECTED);
            });
    }

    @Test
    @DisplayName("SLEEP_FRONTIER: a block moved past bedtime is rejected")
    void moved_past_bedtime_rejected() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, BEDTIME.minusMinutes(30), BEDTIME.plusMinutes(30))));

        assertThat(guard.check(propuesta, context).breaches()).singleElement()
            .satisfies(w -> assertThat(w.wall()).isEqualTo(ProposalWall.SLEEP_FRONTIER));
    }

    @Test
    @DisplayName("AGENDA_READ_ONLY: a block moved onto a read-only AGENDA window is rejected")
    void moved_onto_agenda_rejected() {
        OccupiedInterval agendaWall = new OccupiedInterval(
            UUID.randomUUID(), WAKE.plusMinutes(120), WAKE.plusMinutes(180), true);
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of(agendaWall));
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, WAKE.plusMinutes(130), WAKE.plusMinutes(160))));

        assertThat(guard.check(propuesta, context).breaches()).singleElement()
            .satisfies(w -> assertThat(w.wall()).isEqualTo(ProposalWall.AGENDA_READ_ONLY));
    }

    private static AgendaProposalContext context(List<AgendaBlock> candidates, Set<UUID> wigIds,
                                                 List<OccupiedInterval> agendaWalls) {
        return new AgendaProposalContext(candidates, WAKE, BEDTIME, agendaWalls, wigIds, 3, "NEUTRAL", Map.of());
    }

    private static AgendaBlock block(UUID id, OffsetDateTime start, OffsetDateTime end) {
        return new AgendaBlock(id, start, end, false, false, "reason");
    }

    private static BlockDecision keep(UUID id) {
        return new BlockDecision(id, Placement.KEEP, null, null, null);
    }

    private static BlockDecision move(UUID id, OffsetDateTime start, OffsetDateTime end) {
        return new BlockDecision(id, Placement.MOVE, start, end, "moved");
    }

    private static BlockDecision drop(UUID id) {
        return new BlockDecision(id, Placement.DROP, null, null, null);
    }
}
