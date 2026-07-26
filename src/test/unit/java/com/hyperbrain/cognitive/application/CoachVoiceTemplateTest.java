package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.domain.model.CoachSignals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoachVoiceTemplate — deterministic line citing hard signals, never free prose (ADR-029 D3)")
class CoachVoiceTemplateTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 24);

    private final CoachVoiceTemplate template = new CoachVoiceTemplate();

    @Test
    @DisplayName("a WIG-hit day cites the streak and reinforces the lead measure")
    void wig_hit_cites_streak() {
        String line = template.render(new CoachSignals(USER, DAY, true, false, 3, 0.8));

        assertThat(line).contains("WIG").contains("3 días");
    }

    @Test
    @DisplayName("a WIG-hit day with a one-day streak reads in the singular")
    void wig_hit_singular_streak() {
        String line = template.render(new CoachSignals(USER, DAY, true, false, 1, 0.8));

        assertThat(line).contains("racha de 1 día").doesNotContain("1 días");
    }

    @Test
    @DisplayName("an abandoned day confronts the gap and cites the adherence percentage")
    void abandoned_cites_adherence() {
        String line = template.render(new CoachSignals(USER, DAY, false, true, 0, 0.25));

        assertThat(line).contains("25%").contains("WIG");
    }

    @Test
    @DisplayName("a missed-WIG, non-abandoned day names the broken streak and the adherence")
    void missed_wig_names_broken_streak() {
        String line = template.render(new CoachSignals(USER, DAY, false, false, 0, 0.6));

        assertThat(line).contains("racha se rompió").contains("60%").contains("WIG");
    }

    @Test
    @DisplayName("the line is deterministic for the same signals")
    void deterministic() {
        CoachSignals signals = new CoachSignals(USER, DAY, true, false, 2, 0.9);

        assertThat(template.render(signals)).isEqualTo(template.render(signals));
    }
}
