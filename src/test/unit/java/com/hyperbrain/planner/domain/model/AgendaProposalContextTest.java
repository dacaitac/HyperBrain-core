package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgendaProposalContext — the run's read model, and the bands it carries")
class AgendaProposalContextTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 8, 8, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = WAKE.plusHours(16);
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("a block's band is looked up by its run id")
    void a_band_is_looked_up_by_block_id() {
        RetimingBand household = new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14));

        AgendaProposalContext context = context(Map.of(A, household));

        assertThat(context.band(A)).usingRecursiveComparison().isEqualTo(household);
    }

    @Test
    @DisplayName("a block that belongs to no band reports none — unconfined, not confined to nothing")
    void a_block_without_a_band_reports_none() {
        AgendaProposalContext context = context(Map.of());

        assertThat(context.band(A)).isNull();
        assertThat(context.band(B)).isNull();
    }

    @Test
    @DisplayName("an absent band map is an empty one, so the LLM road never has to null-check it")
    void a_null_band_map_becomes_empty() {
        AgendaProposalContext context = context(null);

        assertThat(context.bands()).isEmpty();
        assertThat(context.band(A)).isNull();
    }

    @Test
    @DisplayName("the band map is copied on the way in and immutable on the way out")
    void the_band_map_is_defensively_copied() {
        Map<UUID, RetimingBand> mutable = new HashMap<>();
        mutable.put(A, new RetimingBand("Casa", WAKE.plusHours(12), WAKE.plusHours(14)));

        AgendaProposalContext context = context(mutable);
        mutable.put(B, new RetimingBand("Oficio", WAKE.plusHours(4), WAKE.plusHours(6)));

        assertThat(context.bands()).hasSize(1);
        assertThat(context.band(B)).isNull();
        assertThatThrownBy(() -> context.bands().put(B, context.band(A)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AgendaProposalContext context(Map<UUID, RetimingBand> bands) {
        return new AgendaProposalContext(
            List.of(new AgendaBlock(A, WAKE, WAKE.plusMinutes(60), false, false, "reason")),
            WAKE, BEDTIME, List.of(), List.of(), Set.of(), 3, "NEUTRAL", Map.of(), bands);
    }
}
