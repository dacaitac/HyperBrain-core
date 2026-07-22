package com.hyperbrain.cognitive.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringAiLlmGateway.extractText — robust text extraction across content blocks (Sonnet 5)")
class SpringAiLlmGatewayTest {

    private static final String JSON = "{\"decisions\":[]}";

    @Test
    @DisplayName("a single text generation is returned verbatim")
    void single_text_block() {
        ChatResponse response = new ChatResponse(List.of(generation(JSON)));

        assertThat(SpringAiLlmGateway.extractText(response)).isEqualTo(JSON);
    }

    @Test
    @DisplayName("a reasoning block preceding the answer: the LAST non-blank text (the JSON) is taken")
    void reasoning_then_answer_takes_the_answer() {
        ChatResponse response = new ChatResponse(List.of(
            generation("Let me think about the day... the WIG goes first."),
            generation(JSON)));

        assertThat(SpringAiLlmGateway.extractText(response)).isEqualTo(JSON);
    }

    @Test
    @DisplayName("an empty completion (a null-text generation) yields the empty string")
    void null_text_generation_is_empty() {
        ChatResponse response = new ChatResponse(List.of(nullTextGeneration()));

        assertThat(SpringAiLlmGateway.extractText(response)).isEmpty();
    }

    @Test
    @DisplayName("no generations yields the empty string")
    void no_generations_is_empty() {
        assertThat(SpringAiLlmGateway.extractText(new ChatResponse(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("a null response yields the empty string")
    void null_response_is_empty() {
        assertThat(SpringAiLlmGateway.extractText(null)).isEmpty();
    }

    private static Generation generation(String text) {
        return new Generation(new AssistantMessage(text, Map.of()));
    }

    private static Generation nullTextGeneration() {
        return new Generation(new AssistantMessage(null, Map.of()));
    }
}
