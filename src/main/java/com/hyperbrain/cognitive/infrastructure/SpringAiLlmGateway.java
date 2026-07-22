package com.hyperbrain.cognitive.infrastructure;

import com.hyperbrain.cognitive.domain.LlmGatewayException;
import com.hyperbrain.cognitive.domain.model.LlmPrompt;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The Spring AI adapter for {@link LlmGateway} (ADR-005): maps the provider-agnostic prompt onto a
 * {@link ChatClient} call. Provider selection (Anthropic Opus in dev, configurable) and timeouts are
 * configured on the underlying {@code ChatModel} / HTTP client via the model starter, so this adapter
 * stays vendor-neutral.
 *
 * <p><b>Inert until H4.</b> It is wired only by {@link CognitiveLlmAutoConfiguration}, which requires
 * both {@code app.cognitive.llm-propose.enabled=true} and a {@code ChatClient.Builder} bean — the latter
 * exists only once a model provider starter is on the classpath (added in H4 when Daniel provisions the
 * key). In H3 no provider is present, so this bean is never created and every test drives a deterministic
 * stub instead. No real model is ever called before H4.
 *
 * <p><b>Reproducibility.</b> No sampling temperature is set (Opus 4.8 removes it): determinism comes from
 * bounding the output space — the closed {@code blockId} set, the strict JSON schema and the
 * {@code ProposalWallGuard} — not from sampling parameters.
 */
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(LlmPrompt prompt) {
        String content;
        try {
            content = chatClient.prompt()
                .system(prompt.system())
                .user(prompt.user())
                .call()
                .content();
        } catch (RuntimeException ex) {
            throw new LlmGatewayException("LLM call failed: " + ex.getMessage(), ex);
        }
        if (content == null || content.isBlank()) {
            throw new LlmGatewayException("LLM returned an empty completion");
        }
        return content;
    }
}
