package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.domain.model.CoachSignals;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoachVoicePromptBuilder — anchored to the hard signals, not free prose (ADR-029 D3)")
class CoachVoicePromptBuilderTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CoachVoicePromptBuilder builder = new CoachVoicePromptBuilder(new ObjectMapper());

    @Test
    @DisplayName("the user message carries every hard signal as trusted control data")
    void user_message_carries_hard_signals() {
        CoachSignals signals =
            new CoachSignals(USER, LocalDate.of(2026, 7, 24), true, false, 3, 0.8);

        LlmPrompt prompt = builder.build(signals);

        assertThat(prompt.user())
            .contains("\"wig_hit\" : true")
            .contains("\"abandoned\" : false")
            .contains("\"wig_streak\" : 3")
            .contains("\"adherence\" : 0.8")
            .contains("2026-07-24");
    }

    @Test
    @DisplayName("the system message frames confrontation of the 4DX gap and forbids inventing data")
    void system_message_frames_confrontation() {
        assertThat(CoachVoicePromptBuilder.SYSTEM)
            .contains("CONFRONTAR")
            .contains("WIG")
            .contains("NUNCA inventes");
    }

    @Test
    @DisplayName("null signals are rejected")
    void null_signals_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
