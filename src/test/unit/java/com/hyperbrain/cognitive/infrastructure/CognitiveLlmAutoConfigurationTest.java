package com.hyperbrain.cognitive.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.application.AgendaProposalPromptBuilder;
import com.hyperbrain.cognitive.application.AgendaPropuestaParser;
import com.hyperbrain.cognitive.application.ProposalTelemetry;
import com.hyperbrain.cognitive.application.ProposalWallGuard;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.planner.domain.port.out.AgendaProposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the order-safe, crash-proof wiring of the LLM propose tier (the regression that crashlooped
 * prod: flag on, provider present, but the {@code LlmGateway} bean skipped by an ill-ordered
 * {@code @ConditionalOnBean}). Uses {@link ApplicationContextRunner} so the real autoconfiguration
 * ordering is exercised without a Spring Boot context or any Anthropic call.
 */
@DisplayName("CognitiveLlmAutoConfiguration — order-safe, degrade-not-crash wiring (H3/H4)")
class CognitiveLlmAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration.class,
            CognitiveLlmAutoConfiguration.class))
        .withUserConfiguration(Collaborators.class);

    @Test
    @DisplayName("flag on + a ChatModel present → the gateway and the proposer are both created (LLM active)")
    void provider_present_creates_gateway_and_proposer() {
        runner
            .withPropertyValues("app.cognitive.llm-propose.enabled=true")
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LlmGateway.class);
                assertThat(context).hasSingleBean(AgendaProposer.class);
            });
    }

    @Test
    @DisplayName("flag on + NO provider → the context loads without crashing, with no proposer (DEGRADED)")
    void provider_absent_degrades_without_crashing() {
        runner
            .withPropertyValues("app.cognitive.llm-propose.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(LlmGateway.class);
                assertThat(context).doesNotHaveBean(AgendaProposer.class);
            });
    }

    @Test
    @DisplayName("flag on + a supplied LlmGateway stub (tests) → the proposer wires to the stub, no real adapter")
    void supplied_stub_backs_the_proposer() {
        LlmGateway stub = prompt -> "{\"decisions\":[]}";
        runner
            .withPropertyValues("app.cognitive.llm-propose.enabled=true")
            .withBean(LlmGateway.class, () -> stub)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LlmGateway.class);
                assertThat(context.getBean(LlmGateway.class)).isSameAs(stub);
                assertThat(context).hasSingleBean(AgendaProposer.class);
            });
    }

    @Test
    @DisplayName("flag off → neither bean exists (planner stays on the humanized floor)")
    void flag_off_creates_nothing() {
        runner
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(LlmGateway.class);
                assertThat(context).doesNotHaveBean(AgendaProposer.class);
            });
    }

    /** The stateless cognitive collaborators the proposer needs, mirroring their component scan. */
    @Configuration
    static class Collaborators {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AgendaProposalPromptBuilder promptBuilder(ObjectMapper mapper) {
            return new AgendaProposalPromptBuilder(mapper);
        }

        @Bean
        AgendaPropuestaParser parser(ObjectMapper mapper) {
            return new AgendaPropuestaParser(mapper);
        }

        @Bean
        ProposalWallGuard wallGuard() {
            return new ProposalWallGuard();
        }

        @Bean
        ProposalTelemetry telemetry(ObjectMapper mapper) {
            return new ProposalTelemetry(mapper);
        }
    }
}
