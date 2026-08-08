package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.application.ProposalTelemetry.DegradeReason;
import com.hyperbrain.cognitive.application.ProposalWallGuard.ProposalWall;
import com.hyperbrain.cognitive.application.ProposalWallGuard.WallGuardResult;
import com.hyperbrain.cognitive.domain.LlmGatewayException;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.cognitive.infrastructure.CommitteePromptProperties;
import com.hyperbrain.cognitive.infrastructure.CommitteePromptProperties.SpecialContext;
import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.AgendaProposalContext;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.RetimingBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("AgendaProposalService — propose → validate → DEGRADED (H3, stub gateway)")
class AgendaProposalServiceTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgendaProposalPromptBuilder promptBuilder = new AgendaProposalPromptBuilder(
        objectMapper, new CommitteePromptProperties(100, SpecialContext.NONE));
    private final AgendaPropuestaParser parser = new AgendaPropuestaParser(objectMapper);
    private final ProposalWallGuard wallGuard = new ProposalWallGuard();
    private final ProposalTelemetry telemetry = mock(ProposalTelemetry.class);

    private AgendaProposalService service(LlmGateway gateway) {
        return new AgendaProposalService(gateway, promptBuilder, parser, wallGuard, telemetry, 0.8);
    }

    @Test
    @DisplayName("a valid proposal materializes as-is, with the coach note routed to the block reason")
    void valid_proposal_materializes() {
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T09:00:00Z","end":"2026-07-10T10:00:00Z","coach_note":"peak energy"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(twoBlockContext());

        assertThat(result).isPresent();
        assertThat(result.get().blocks()).hasSize(2);
        AgendaBlock moved = result.get().blocks().stream()
            .filter(b -> b.executableId().equals(A)).findFirst().orElseThrow();
        assertThat(moved.start()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(moved.reason()).isEqualTo("peak energy");
        // chronological order: B (KEEP 08:00) precedes A (MOVE 09:00)
        assertThat(result.get().blocks().get(0).executableId()).isEqualTo(B);
        verify(telemetry).accepted(twoBlockContext());
    }

    @Test
    @DisplayName("a proposal dropping a non-WIG block materializes the surviving blocks")
    void drop_non_wig_materializes_survivors() {
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"KEEP"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"DROP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(twoBlockContext());

        assertThat(result).isPresent();
        assertThat(result.get().blocks()).singleElement()
            .satisfies(b -> assertThat(b.executableId()).isEqualTo(A));
    }

    @Test
    @DisplayName("backstop: dropping more than the max-drop fraction of non-WIG blocks degrades (gutted day)")
    void excessive_drop_degrades() {
        // Both non-WIG candidates dropped = 100% > 0.8 threshold → a gutted day → DEGRADED to the floor.
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"DROP"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"DROP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(twoBlockContext());

        assertThat(result).isEmpty();
        verify(telemetry).degraded(org.mockito.ArgumentMatchers.eq(twoBlockContext()),
            org.mockito.ArgumentMatchers.eq(DegradeReason.EXCESSIVE_DROP),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("an invented block id degrades (structural identity)")
    void invented_block_degrades() {
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"KEEP"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"},
              {"block_id":"99999999-9999-9999-9999-999999999999","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(twoBlockContext());

        assertThat(result).isEmpty();
        assertThat(interventionWalls()).contains(ProposalWall.STRUCTURAL_IDENTITY);
    }

    @Test
    @DisplayName("dropping the WIG degrades (WIG protected)")
    void wig_drop_degrades() {
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"DROP"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(wigContext());

        assertThat(result).isEmpty();
        assertThat(interventionWalls()).contains(ProposalWall.WIG_PROTECTED);
    }

    @Test
    @DisplayName("a block moved past bedtime degrades (sleep frontier)")
    void sleep_breach_degrades() {
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T22:45:00Z","end":"2026-07-10T23:45:00Z"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(twoBlockContext());

        assertThat(result).isEmpty();
        assertThat(interventionWalls()).contains(ProposalWall.SLEEP_FRONTIER);
    }

    @Test
    @DisplayName("a block overlapping a read-only AGENDA window degrades (AGENDA)")
    void agenda_overlap_degrades() {
        OccupiedInterval agendaWall = new OccupiedInterval(
            UUID.randomUUID(), WAKE.plusMinutes(120), WAKE.plusMinutes(180), true);
        AgendaProposalContext context = new AgendaProposalContext(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(90))),
            WAKE, BEDTIME, List.of(agendaWall), List.of(), Set.of(), 3, "NEUTRAL", Map.of(), Map.of());
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T09:10:00Z","end":"2026-07-10T09:40:00Z"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(context);

        assertThat(result).isEmpty();
        assertThat(interventionWalls()).contains(ProposalWall.AGENDA_READ_ONLY);
    }

    @Test
    @DisplayName("a block moved onto a window the user already owns degrades (OCCUPIED)")
    void occupied_block_overlap_degrades() {
        OccupiedInterval userBlock = new OccupiedInterval(
            UUID.randomUUID(), WAKE.plusMinutes(120), WAKE.plusMinutes(180), false);
        AgendaProposalContext context = new AgendaProposalContext(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(90))),
            WAKE, BEDTIME, List.of(), List.of(userBlock), Set.of(), 3, "NEUTRAL", Map.of(), Map.of());
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T09:10:00Z","end":"2026-07-10T09:40:00Z"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(context);

        assertThat(result).isEmpty();
        assertThat(interventionWalls()).contains(ProposalWall.OCCUPIED_BLOCK);
    }

    @Test
    @DisplayName("a block moved out of its band degrades the whole day (BAND)")
    void band_breach_degrades() {
        // The production case, end to end: the evening band moved to the first hour of the day. The
        // rest of the proposal is impeccable, and it still degrades — the guard is all-or-nothing.
        RetimingBand household = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14));
        AgendaProposalContext context = new AgendaProposalContext(
            List.of(block(A, WAKE.plusHours(12), WAKE.plusHours(13)),
                block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(90))),
            WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL", Map.of(),
            Map.of(A, household));
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T07:00:00Z","end":"2026-07-10T08:00:00Z"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(context);

        assertThat(result).isEmpty();
        assertThat(interventionWalls()).contains(ProposalWall.BAND_CONFINEMENT);
    }

    @Test
    @DisplayName("a block retimed inside its band is accepted — the arrangement stays the model's")
    void retiming_inside_the_band_is_accepted() {
        RetimingBand household = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14));
        AgendaProposalContext context = new AgendaProposalContext(
            List.of(block(A, WAKE.plusHours(12), WAKE.plusHours(13))),
            WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL", Map.of(),
            Map.of(A, household));
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T20:00:00Z","end":"2026-07-10T21:00:00Z","coach_note":"Cierra en casa"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(context);

        assertThat(result).isPresent();
        assertThat(result.get().blocks()).singleElement().satisfies(moved -> {
            assertThat(moved.start()).isEqualTo(WAKE.plusHours(13));
            assertThat(moved.reason()).isEqualTo("Cierra en casa");
        });
    }

    @Test
    @DisplayName("invalid JSON degrades and records INVALID_JSON")
    void invalid_json_degrades() {
        Optional<Agenda> result = service(prompt -> "I cannot do that.").propose(twoBlockContext());

        assertThat(result).isEmpty();
        verify(telemetry).degraded(org.mockito.ArgumentMatchers.eq(twoBlockContext()),
            org.mockito.ArgumentMatchers.eq(DegradeReason.INVALID_JSON),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("a gateway failure (timeout/transport) degrades and records GATEWAY_FAILURE")
    void gateway_failure_degrades() {
        LlmGateway failing = prompt -> {
            throw new LlmGatewayException("read timed out");
        };

        Optional<Agenda> result = service(failing).propose(twoBlockContext());

        assertThat(result).isEmpty();
        verify(telemetry).degraded(org.mockito.ArgumentMatchers.eq(twoBlockContext()),
            org.mockito.ArgumentMatchers.eq(DegradeReason.GATEWAY_FAILURE),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("ADR-029 D1: propose() invokes the gateway exactly once per generation, never once per "
        + "committee role")
    void propose_invokes_gateway_exactly_once() {
        LlmGateway gateway = mock(LlmGateway.class);
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"KEEP"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";
        org.mockito.Mockito.when(gateway.complete(org.mockito.ArgumentMatchers.any())).thenReturn(json);

        Optional<Agenda> result = service(gateway).propose(twoBlockContext());

        assertThat(result).isPresent();
        verify(gateway, times(1)).complete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an empty candidate set degrades without calling the gateway")
    void empty_candidates_degrade_without_call() {
        LlmGateway neverCalled = prompt -> {
            throw new AssertionError("gateway must not be called for an empty run");
        };
        AgendaProposalContext empty = new AgendaProposalContext(
            List.of(), WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL", Map.of(), Map.of());

        assertThat(service(neverCalled).propose(empty)).isEmpty();
    }

    @Test
    @DisplayName("a MOVE carries the themed container's membership and theme over, never re-composing it")
    void move_preserves_membership_and_theme() {
        // Given a themed container the floor grouped (A anchors, C rides along)
        AgendaBlock themed = new AgendaBlock(A, WAKE, WAKE.plusMinutes(60), false, false,
            "floor reason", List.of(C), "Deep work morning");
        AgendaProposalContext context = new AgendaProposalContext(
            List.of(themed, block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL", Map.of(), Map.of());
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"MOVE",
               "start":"2026-07-10T09:00:00Z","end":"2026-07-10T10:00:00Z","coach_note":"peak energy"},
              {"block_id":"22222222-2222-2222-2222-222222222222","placement":"KEEP"}
            ]}""";

        // When the model retimes it
        Optional<Agenda> result = service(prompt -> json).propose(context);

        // Then only the window and the reason change: the membership and the theme survive. Rebuilding
        // the block from its scalar fields used to drop the companions silently.
        AgendaBlock moved = result.orElseThrow().blocks().stream()
            .filter(b -> b.executableId().equals(A)).findFirst().orElseThrow();
        assertThat(moved.additionalMembers()).containsExactly(C);
        assertThat(moved.theme()).isEqualTo("Deep work morning");
        assertThat(moved.start()).isEqualTo(OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(moved.reason()).isEqualTo("peak energy");
    }

    @Test
    @DisplayName("a KEEP leaves the themed container untouched, membership included")
    void keep_preserves_membership_and_theme() {
        AgendaBlock themed = new AgendaBlock(A, WAKE, WAKE.plusMinutes(60), false, false,
            "floor reason", List.of(C), "Deep work morning");
        AgendaProposalContext context = new AgendaProposalContext(
            List.of(themed), WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL", Map.of(), Map.of());
        String json = """
            {"decisions":[
              {"block_id":"11111111-1111-1111-1111-111111111111","placement":"KEEP"}
            ]}""";

        Optional<Agenda> result = service(prompt -> json).propose(context);

        assertThat(result.orElseThrow().blocks()).singleElement()
            .usingRecursiveComparison()
            .isEqualTo(themed);
    }

    private List<ProposalWall> interventionWalls() {
        ArgumentCaptor<WallGuardResult> captor = ArgumentCaptor.forClass(WallGuardResult.class);
        verify(telemetry).intervened(org.mockito.ArgumentMatchers.any(), captor.capture());
        return captor.getValue().breaches().stream().map(ProposalWallGuard.WallBreach::wall).toList();
    }

    private static AgendaProposalContext twoBlockContext() {
        return new AgendaProposalContext(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL",
            Map.of(A, "Write the report", B, "Review PRs"), Map.of());
    }

    private static AgendaProposalContext wigContext() {
        return new AgendaProposalContext(
            List.of(block(A, WAKE, WAKE.plusMinutes(60)), block(B, WAKE.plusMinutes(60), WAKE.plusMinutes(120))),
            WAKE, BEDTIME, List.of(), List.of(), Set.of(A), 3, "NEUTRAL", Map.of(), Map.of());
    }

    private static AgendaBlock block(UUID id, OffsetDateTime start, OffsetDateTime end) {
        return new AgendaBlock(id, start, end, false, false, "floor reason");
    }
}
