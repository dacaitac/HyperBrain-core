package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.HumanizationContext;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgendaHumanizer (H1 rules 3 & 6, post-placement transforms)")
class AgendaHumanizerTest {

    private static final OffsetDateTime WINDOW_START =
        OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime WINDOW_END =
        OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);

    private final AgendaHumanizer humanizer = new AgendaHumanizer();

    @Test
    @DisplayName("rule 3: a sub-minimum sliver is dropped and reported, the viable block survives")
    void drops_slivers_below_minimum() {
        UUID sliverId = UUID.randomUUID();
        UUID viableId = UUID.randomUUID();
        AgendaBlock sliver = block(sliverId, WINDOW_START, WINDOW_START.plusMinutes(10), false);
        AgendaBlock viable = block(viableId, WINDOW_START.plusMinutes(15), WINDOW_START.plusMinutes(45), false);
        Agenda raw = agenda(List.of(sliver, viable));

        Agenda humanized = humanizer.humanize(raw, context(Map.of()), settings(15, 1.0));

        assertThat(humanized.blocks()).extracting(AgendaBlock::executableId).containsExactly(viableId);
        assertThat(humanized.excluded())
            .contains(new ExcludedExecutable(sliverId, ExclusionReason.BELOW_MIN_BLOCK));
    }

    @Test
    @DisplayName("rule 3: a WIG shorter than the minimum is never dropped as a sliver")
    void never_drops_a_wig_sliver() {
        UUID wigId = UUID.randomUUID();
        AgendaBlock wig = block(wigId, WINDOW_START, WINDOW_START.plusMinutes(10), true);
        Agenda raw = agenda(List.of(wig));

        Agenda humanized = humanizer.humanize(raw, context(Map.of()), settings(15, 1.0));

        assertThat(humanized.blocks()).extracting(AgendaBlock::executableId).containsExactly(wigId);
        assertThat(humanized.excluded()).isEmpty();
    }

    @Test
    @DisplayName("rule 6: the lowest-priority block is trimmed to bring the day within the occupancy cap")
    void trims_lowest_priority_to_occupancy_cap() {
        // Window = 100 min (07:00-08:40); cap 0.85 -> 85 min. Blocks 60 + 40 = 100 > 85.
        OffsetDateTime end = WINDOW_START.plusMinutes(100);
        UUID keepId = UUID.randomUUID();
        UUID trimId = UUID.randomUUID();
        AgendaBlock keep = block(keepId, WINDOW_START, WINDOW_START.plusMinutes(60), false);
        AgendaBlock trim = block(trimId, WINDOW_START.plusMinutes(60), WINDOW_START.plusMinutes(100), false);
        Agenda raw = agenda(List.of(keep, trim));
        HumanizationContext ctx = new HumanizationContext(
            WINDOW_START, end, Map.of(keepId, 0.9, trimId, 0.3));

        Agenda humanized = humanizer.humanize(raw, ctx, settings(0, 0.85));

        // The low-priority 40-min block is shed; the day drops to 60 min (0.60) within the cap.
        assertThat(humanized.blocks()).extracting(AgendaBlock::executableId).containsExactly(keepId);
        assertThat(humanized.excluded())
            .contains(new ExcludedExecutable(trimId, ExclusionReason.OVER_OCCUPANCY_CAP));
    }

    @Test
    @DisplayName("rule 6: a day already within the cap is left untouched")
    void leaves_day_within_cap_untouched() {
        AgendaBlock a = block(UUID.randomUUID(), WINDOW_START, WINDOW_START.plusMinutes(60), false);
        Agenda raw = agenda(List.of(a));

        Agenda humanized = humanizer.humanize(raw, context(Map.of()), settings(0, 0.85));

        assertThat(humanized.blocks()).hasSize(1);
        assertThat(humanized.excluded()).isEmpty();
    }

    private static AgendaBlock block(UUID id, OffsetDateTime start, OffsetDateTime end, boolean wig) {
        return new AgendaBlock(id, start, end, wig, false, "test block");
    }

    private static Agenda agenda(List<AgendaBlock> blocks) {
        return new Agenda(blocks, List.of(), List.of(), "criterion", false);
    }

    private static HumanizationContext context(Map<UUID, Double> priorities) {
        return new HumanizationContext(WINDOW_START, WINDOW_END, priorities);
    }

    private static HumanizationSettings settings(int minBlockMinutes, double occupancyMax) {
        return new HumanizationSettings(0, List.of(), minBlockMinutes, 0.0, 0.0, occupancyMax);
    }
}
