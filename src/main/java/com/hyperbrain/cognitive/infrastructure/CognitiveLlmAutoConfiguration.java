package com.hyperbrain.cognitive.infrastructure;

import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires the real Spring AI {@link LlmGateway} adapter — but only in H4 and beyond. As an
 * autoconfiguration it is evaluated <em>after</em> the model provider's own autoconfiguration, so
 * {@link ConditionalOnBean}({@code ChatClient.Builder}) resolves correctly: the builder bean exists only
 * once a provider starter (e.g. {@code spring-ai-starter-model-anthropic}) is on the classpath.
 *
 * <p>In H3 there is no provider and the feature flag is off, so this contributes no bean and the
 * cognitive proposer is driven exclusively by a deterministic stub in tests. The
 * {@link ConditionalOnMissingBean} lets a test (or a future alternative provider) supply its own
 * {@link LlmGateway} without colliding with this one.
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(name = "app.cognitive.llm-propose.enabled", havingValue = "true")
public class CognitiveLlmAutoConfiguration {

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean(LlmGateway.class)
    public LlmGateway springAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        return new SpringAiLlmGateway(chatClientBuilder);
    }
}
