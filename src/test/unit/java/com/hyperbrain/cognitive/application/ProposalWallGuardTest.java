package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.application.AgendaPropuesta.BlockDecision;
import com.hyperbrain.cognitive.application.AgendaPropuesta.Placement;
import com.hyperbrain.cognitive.application.ProposalWallGuard.ProposalWall;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallGuardResult;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;
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

    @Test
    @DisplayName("OCCUPIED_BLOCK: a block moved onto a window the user already owns is rejected")
    void moved_onto_occupied_block_rejected() {
        // The production case: Daniel's own block holds 09:00–10:00 and the model moves work onto it.
        OccupiedInterval userBlock = new OccupiedInterval(
            UUID.randomUUID(), WAKE.plusMinutes(120), WAKE.plusMinutes(180), false);
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of(), List.of(userBlock));
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, WAKE.plusMinutes(150), WAKE.plusMinutes(210))));

        assertThat(guard.check(propuesta, context).breaches()).singleElement()
            .satisfies(w -> {
                assertThat(w.blockId()).isEqualTo(A);
                assertThat(w.wall()).isEqualTo(ProposalWall.OCCUPIED_BLOCK);
            });
    }

    @Test
    @DisplayName("OCCUPIED_BLOCK: a KEPT block the floor already laid clear of the wall passes")
    void kept_block_beside_occupied_block_passes() {
        // Butting up against the wall is not overlapping it: the floor lays windows exactly like this,
        // so a keep-everything proposal must never be degraded by the new wall.
        OccupiedInterval userBlock = new OccupiedInterval(
            UUID.randomUUID(), WAKE.plusMinutes(60), WAKE.plusMinutes(120), false);
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of(), List.of(userBlock));

        assertThat(guard.check(new AgendaPropuesta(List.of(keep(A))), context).clean()).isTrue();
    }

    @Test
    @DisplayName("OCCUPIED_BLOCK: dropping a non-WIG block whose window is now occupied is still allowed")
    void drop_over_occupied_block_passes() {
        OccupiedInterval userBlock = new OccupiedInterval(
            UUID.randomUUID(), WAKE, WAKE.plusMinutes(60), false);
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Set.of(), List.of(), List.of(userBlock));

        assertThat(guard.check(new AgendaPropuesta(List.of(drop(A))), context).clean()).isTrue();
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a block moved out of its band is rejected")
    void moved_out_of_band_rejected() {
        // The production case: «Casa» is the evening band, and the model moved it to seven in the
        // morning — the band travelling with the movement, so the day was named after a shape it had
        // lost.
        RetimingBand household = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE.plusHours(12), WAKE.plusHours(13))), Map.of(A, household));
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(move(A, WAKE, WAKE.plusMinutes(60))));

        assertThat(guard.check(propuesta, context).breaches()).singleElement()
            .satisfies(w -> {
                assertThat(w.blockId()).isEqualTo(A);
                assertThat(w.wall()).isEqualTo(ProposalWall.BAND_CONFINEMENT);
            });
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a block moved anywhere inside its band passes — retiming is the model's")
    void moved_within_band_passes() {
        RetimingBand goal = new RetimingBand("Meta de la mañana", WAKE, WAKE.plusHours(3));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Map.of(A, goal));
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, WAKE.plusMinutes(90), WAKE.plusMinutes(150))));

        assertThat(guard.check(propuesta, context).clean()).isTrue();
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a block that spills over its band's edge is rejected")
    void moved_across_the_band_edge_rejected() {
        RetimingBand goal = new RetimingBand("Meta de la mañana", WAKE, WAKE.plusHours(2));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Map.of(A, goal));
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, WAKE.plusMinutes(90), WAKE.plusMinutes(150))));

        assertThat(guard.check(propuesta, context).breaches()).singleElement()
            .satisfies(w -> assertThat(w.wall()).isEqualTo(ProposalWall.BAND_CONFINEMENT));
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a KEPT block is never judged against its band")
    void kept_block_is_not_band_checked() {
        // The floor's own placement is not the model's doing: degrading a day for it would be a
        // self-inflicted outage. Here the candidate sits outside the band it was given.
        RetimingBand narrow = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(13));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Map.of(A, narrow));

        assertThat(guard.check(new AgendaPropuesta(List.of(keep(A))), context).clean()).isTrue();
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a block that belongs to no band may be moved anywhere within the walls")
    void unbanded_block_moves_freely() {
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Map.of());
        AgendaPropuesta propuesta = new AgendaPropuesta(List.of(
            move(A, WAKE.plusHours(9), WAKE.plusHours(10))));

        assertThat(guard.check(propuesta, context).clean()).isTrue();
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: dropping a block is a disposition, never a retiming")
    void dropping_a_banded_block_passes() {
        RetimingBand household = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE.plusHours(12), WAKE.plusHours(13))), Map.of(A, household));

        assertThat(guard.check(new AgendaPropuesta(List.of(drop(A))), context).clean()).isTrue();
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a proposal that moves nothing can never breach a band, whatever "
        + "the bands say")
    void a_proposal_that_moves_nothing_never_breaches_a_band() {
        // The self-inflicted-outage guard, at the scale of a whole day: every candidate sits outside
        // the band it carries — a state the resolvers make impossible, and which must still not degrade
        // a day when the model proposes no movement at all.
        RetimingBand evening = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14));
        RetimingBand night = new RetimingBand("Cierre del día", WAKE.plusHours(14), WAKE.plusHours(15));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)),
                block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            Map.of(A, evening, B, night));

        WallGuardResult result = guard.check(new AgendaPropuesta(List.of(keep(A), keep(B))), context);

        assertThat(result.clean()).isTrue();
        assertThat(result.breaches()).isEmpty();
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a MOVE is judged by the verb, not by whether the geometry changed")
    void a_move_echoing_the_floor_is_still_judged() {
        // The guard trusts the resolvers' invariant (a floor window always sits inside its band) rather
        // than re-deriving it: a MOVE onto the block's own hours is checked like any other. If a band
        // ever came out narrower than the window the floor laid, this is the shape the outage would
        // take — a day degrading over the floor's own placement echoed back by the model.
        RetimingBand narrow = new RetimingBand("Casa", WAKE.plusMinutes(15), WAKE.plusMinutes(45));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Map.of(A, narrow));

        WallGuardResult result = guard.check(
            new AgendaPropuesta(List.of(move(A, WAKE, WAKE.plusMinutes(60)))), context);

        assertThat(result.breaches()).singleElement()
            .satisfies(w -> assertThat(w.wall()).isEqualTo(ProposalWall.BAND_CONFINEMENT));
    }

    @Test
    @DisplayName("BAND_CONFINEMENT: a block that leaves both its band and the day is charged with both")
    void leaving_the_band_and_the_frontier_reports_both() {
        // The breaches are diagnosis, not control flow — the day degrades either way — so a decision
        // that breaks two walls must name both of them in the telemetry.
        RetimingBand morning = new RetimingBand("Meta de la mañana", WAKE, WAKE.plusHours(3));
        AgendaProposalContext context = context(
            List.of(block(A, WAKE, WAKE.plusMinutes(60))), Map.of(A, morning));

        WallGuardResult result = guard.check(
            new AgendaPropuesta(List.of(move(A, BEDTIME.plusMinutes(30), BEDTIME.plusMinutes(90)))),
            context);

        assertThat(result.breaches()).extracting(ProposalWallGuard.WallBreach::wall)
            .containsExactlyInAnyOrder(
                ProposalWall.BAND_CONFINEMENT, ProposalWall.SLEEP_FRONTIER);
    }

    private static AgendaProposalContext context(List<AgendaBlock> candidates, Set<UUID> wigIds,
                                                 List<OccupiedInterval> agendaWalls) {
        return context(candidates, wigIds, agendaWalls, List.of());
    }

    private static AgendaProposalContext context(List<AgendaBlock> candidates,
                                                 Map<UUID, RetimingBand> bands) {
        return new AgendaProposalContext(candidates, WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3,
            "NEUTRAL", Map.of(), bands, List.of());
    }

    private static AgendaProposalContext context(List<AgendaBlock> candidates, Set<UUID> wigIds,
                                                 List<OccupiedInterval> agendaWalls,
                                                 List<OccupiedInterval> occupiedWalls) {
        return new AgendaProposalContext(candidates, WAKE, BEDTIME, agendaWalls, occupiedWalls, wigIds, 3,
            "NEUTRAL", Map.of(), Map.of(), List.of());
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
