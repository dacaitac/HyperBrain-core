package com.hyperbrain.cognitive.infrastructure;

import com.hyperbrain.cognitive.application.AgendaProposalPromptBuilder;
import com.hyperbrain.cognitive.application.AgendaProposalService;
import com.hyperbrain.cognitive.application.AgendaPropuestaParser;
import com.hyperbrain.cognitive.application.ProposalTelemetry;
import com.hyperbrain.cognitive.application.ProposalWallGuard;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.planner.domain.port.out.AgendaProposer;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

/**
 * Wires the LLM propose tier (HU-01c H3/H4) order-safely and resiliently. Two invariants, learned from a
 * prod crashloop where the feature flag was flipped on but the {@code LlmGateway} bean was silently
 * skipped (a {@code @ConditionalOnBean} evaluated before Spring AI had registered the provider, while the
 * flag-gated proposer still demanded the gateway — so the context died):
 *
 * <ol>
 *   <li><b>Order-safe gateway.</b> As an {@link AutoConfiguration} declared {@code after} Spring AI's
 *       {@link ChatClientAutoConfiguration} and {@link AnthropicChatAutoConfiguration}, its
 *       {@link ConditionalOnBean} conditions evaluate only once those have contributed their beans. The
 *       gateway is gated on a {@link ChatModel} bean — the true "provider is configured" signal (it
 *       exists only with {@code spring.ai.model.chat=anthropic} + a key) — not on the prototype
 *       {@code ChatClient.Builder}, whose bean <em>definition</em> is present even when no model exists.</li>
 *   <li><b>Never crash — degrade.</b> The {@link AgendaProposer} is created only
 *       {@link ConditionalOnBean}({@code LlmGateway}); when the provider is absent or mis-wired no
 *       proposer bean exists, so the planner's {@code ObjectProvider<AgendaProposer>} falls back to the
 *       humanized floor (DEGRADED) instead of the context dying. A wiring mistake is a silent floor day
 *       (diagnosable via logs/metric), never a crashloop. Both beans live here — rather than the proposer
 *       being a component-scanned {@code @Service} — precisely so this {@code @ConditionalOnBean} chain
 *       is order-deterministic (a {@code @ConditionalOnBean} on a scanned component is not).</li>
 * </ol>
 *
 * <p>The whole configuration is gated by {@code app.cognitive.llm-propose.enabled}: off (default) means
 * neither bean exists and the planner behaves exactly as H1/H2. Tests supply their own {@code LlmGateway}
 * stub (registered before autoconfiguration), which the {@link ConditionalOnMissingBean} on the real
 * adapter yields to — so the suite never touches a real provider.
 */
@AutoConfiguration(after = { ChatClientAutoConfiguration.class, AnthropicChatAutoConfiguration.class })
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(name = "app.cognitive.llm-propose.enabled", havingValue = "true")
public class CognitiveLlmAutoConfiguration {

    /**
     * The provider's chat model, rebuilt so its default options carry <b>no</b> {@code temperature}.
     * Newer Claude models (Opus 4.8, Sonnet 5) reject the deprecated {@code temperature} parameter, yet
     * Spring AI seeds every request from {@code AnthropicChatModel}'s default {@code temperature} (0.8 by
     * default) — a null runtime option cannot override it, because the option merge ignores nulls. So the
     * fix must live in the model's <em>default</em> options: this bean mirrors the provider
     * autoconfiguration's wiring but strips the temperature, and is {@link Primary} so the
     * {@code ChatClient} uses it. Model and max-tokens still come from {@code spring.ai.anthropic.chat.*},
     * so the outgoing request carries {@code claude-sonnet-5} + max-tokens and omits {@code temperature}
     * ({@code ChatCompletionRequest} is {@code @JsonInclude(NON_NULL)}). Reproducibility is by output-space
     * bounding, never sampling — no {@code top_p}/{@code top_k} is set either.
     */
    @Bean
    @Primary
    @ConditionalOnBean(AnthropicApi.class)
    public ChatModel temperatureFreeAnthropicChatModel(
            AnthropicApi anthropicApi,
            AnthropicChatProperties chatProperties,
            RetryTemplate retryTemplate,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {
        AnthropicChatModel model = AnthropicChatModel.builder()
            .anthropicApi(anthropicApi)
            .defaultOptions(temperatureFreeOptions(chatProperties))
            .toolCallingManager(toolCallingManager)
            .toolExecutionEligibilityPredicate(toolExecutionEligibilityPredicate
                .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
            .retryTemplate(retryTemplate)
            .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
            .build();
        observationConvention.ifAvailable(model::setObservationConvention);
        return model;
    }

    /**
     * The provider's configured chat options with the deprecated {@code temperature} stripped to null,
     * preserving everything else (model, max-tokens). Package-visible so it can be unit-tested without a
     * live provider.
     *
     * @param chatProperties the bound Anthropic chat properties; never null
     * @return options identical to the configured ones but with {@code temperature == null}
     */
    static AnthropicChatOptions temperatureFreeOptions(AnthropicChatProperties chatProperties) {
        AnthropicChatOptions options = AnthropicChatOptions.fromOptions(chatProperties.getOptions());
        options.setTemperature(null);
        return options;
    }

    /**
     * The real Spring AI adapter — created only when a {@link ChatModel} bean is present (the provider is
     * properly configured) and no other {@link LlmGateway} was supplied (e.g. a test stub).
     *
     * @param chatClientBuilder the autoconfigured builder (resolvable because a {@link ChatModel} exists)
     * @return the Spring AI-backed gateway
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnMissingBean(LlmGateway.class)
    public LlmGateway springAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        return new SpringAiLlmGateway(chatClientBuilder);
    }

    /**
     * The cognitive orchestrator — created only when an {@link LlmGateway} bean exists (real adapter or
     * test stub). Its absence is the DEGRADED signal the planner falls back on, so a missing or mis-wired
     * provider never fails the context.
     *
     * @param gateway       the LLM gateway; never null
     * @param promptBuilder the prompt renderer; never null
     * @param parser        the proposal parser; never null
     * @param wallGuard     the bounded wall guard; never null
     * @param telemetry     the pre-validation telemetry; never null
     * @return the agenda proposer backing the planner's inversion port
     */
    @Bean
    @ConditionalOnBean(LlmGateway.class)
    @ConditionalOnMissingBean(AgendaProposer.class)
    public AgendaProposer agendaProposalService(
            LlmGateway gateway,
            AgendaProposalPromptBuilder promptBuilder,
            AgendaPropuestaParser parser,
            ProposalWallGuard wallGuard,
            ProposalTelemetry telemetry,
            @Value("${app.cognitive.max-drop-fraction:0.8}") double maxDropFraction) {
        return new AgendaProposalService(gateway, promptBuilder, parser, wallGuard, telemetry,
            maxDropFraction);
    }
}
