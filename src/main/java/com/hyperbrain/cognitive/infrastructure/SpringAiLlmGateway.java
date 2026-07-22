package com.hyperbrain.cognitive.infrastructure;

import com.hyperbrain.cognitive.domain.LlmGatewayException;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * The Spring AI adapter for {@link LlmGateway} (ADR-005): maps the provider-agnostic prompt onto a
 * {@link ChatClient} call. Provider selection (Anthropic Sonnet, configurable) and timeouts are
 * configured on the underlying {@code ChatModel} / HTTP client via the model starter, so this adapter
 * stays vendor-neutral. No sampling temperature/top-p is sent — reproducibility comes from bounding the
 * output space (the closed {@code blockId} set, the JSON schema and the {@code ProposalWallGuard}).
 *
 * <p><b>Robust text extraction.</b> The adapter reads the full {@link ChatResponse} rather than the
 * convenience {@code .content()} (which returns only the first generation). Newer Claude models can split
 * a response across several content blocks — a reasoning block preceding the answer — and Spring AI maps
 * each block to its own generation; taking the first would miss the JSON. It takes the last non-blank
 * text instead. When the response carries no usable text (an empty or reasoning-only completion, e.g.
 * {@code stop_reason=max_tokens} with no text block), it fails with the finish reason attached, so the
 * proposer degrades to the humanized floor and the telemetry line names the cause.
 */
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(LlmPrompt prompt) {
        ChatResponse response;
        try {
            response = chatClient.prompt()
                .system(prompt.system())
                .user(prompt.user())
                .call()
                .chatResponse();
        } catch (RuntimeException ex) {
            throw new LlmGatewayException("LLM call failed: " + ex.getMessage(), ex);
        }
        String text = extractText(response);
        if (text.isBlank()) {
            throw new LlmGatewayException("LLM returned an empty completion (" + describe(response) + ")");
        }
        return text;
    }

    /**
     * Extracts the assistant's answer text from the response, robust to a model that splits its output
     * across several content blocks: Spring AI turns each block into its own generation, and the final
     * answer is the last non-blank text (a preceding reasoning block, if any, comes first).
     *
     * @param response the chat response; may be null
     * @return the last non-blank generation text, or {@code ""} when the response carries no usable text
     */
    static String extractText(ChatResponse response) {
        if (response == null) {
            return "";
        }
        return response.getResults().stream()
            .map(generation -> generation.getOutput().getText())
            .filter(text -> text != null && !text.isBlank())
            .reduce((first, second) -> second)
            .orElse("");
    }

    /** A short, non-sensitive description of why a completion was unusable (finish reason + block count). */
    private static String describe(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return "no results";
        }
        String finishReason = response.getResult().getMetadata().getFinishReason();
        return "finishReason=" + finishReason + ", results=" + response.getResults().size();
    }
}
