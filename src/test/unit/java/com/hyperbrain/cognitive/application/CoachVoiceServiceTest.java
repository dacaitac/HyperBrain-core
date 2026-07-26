package com.hyperbrain.cognitive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.cognitive.domain.LlmGatewayException;
import com.hyperbrain.cognitive.domain.model.CoachSignals;
import com.hyperbrain.cognitive.domain.model.CoachVoice;
import com.hyperbrain.cognitive.domain.model.ScoreboardView;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.cognitive.domain.port.out.DailyAdherenceQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CoachVoiceService — lazy, cached, degradable coach voice (ADR-029 D3)")
class CoachVoiceServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 24);
    private static final CoachSignals WIG_HIT =
        new CoachSignals(USER, DAY, true, false, 3, 0.8);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoachVoicePromptBuilder promptBuilder = new CoachVoicePromptBuilder(objectMapper);
    private final CoachVoiceTemplate template = new CoachVoiceTemplate();

    @SuppressWarnings("unchecked")
    private CoachVoiceService service(DailyAdherenceQuery query, LlmGateway gateway) {
        ObjectProvider<LlmGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        return new CoachVoiceService(query, promptBuilder, template, provider);
    }

    private static DailyAdherenceQuery queryReturning(Optional<CoachSignals> signals) {
        DailyAdherenceQuery query = mock(DailyAdherenceQuery.class);
        when(query.latestSignals(USER)).thenReturn(signals);
        return query;
    }

    @Test
    @DisplayName("no rollup yet → empty Scoreboard and no voice, never failing")
    void no_signals_render_empty() {
        CoachVoiceService service = service(queryReturning(Optional.empty()), prompt -> "unused");

        assertThat(service.renderScoreboard(USER)).isEqualTo(ScoreboardView.empty());
        assertThat(service.voiceFor(USER)).isEmpty();
    }

    @Test
    @DisplayName("live model line is attached to the Scoreboard, numbers intact")
    void live_model_line_attached() {
        CoachVoiceService service = service(queryReturning(Optional.of(WIG_HIT)), prompt -> "  Vas bien.  ");

        ScoreboardView view = service.renderScoreboard(USER);

        assertThat(view.hasData()).isTrue();
        assertThat(view.wigHit()).isTrue();
        assertThat(view.wigStreak()).isEqualTo(3);
        assertThat(view.adherence()).isEqualTo(0.8);
        assertThat(view.coachVoice()).isPresent();
        assertThat(view.coachVoice().get().source()).isEqualTo(CoachVoice.Source.LLM);
        assertThat(view.coachVoice().get().message()).isEqualTo("Vas bien.");
    }

    @Test
    @DisplayName("LLM failure degrades to the templated line AND the Scoreboard still renders (acceptance)")
    void degrades_to_template_scoreboard_still_renders() {
        LlmGateway failing = prompt -> {
            throw new LlmGatewayException("read timed out");
        };
        CoachVoiceService service = service(queryReturning(Optional.of(WIG_HIT)), failing);

        ScoreboardView view = service.renderScoreboard(USER);

        // The panel renders on its numbers regardless of the voice.
        assertThat(view.hasData()).isTrue();
        assertThat(view.wigHit()).isTrue();
        assertThat(view.wigStreak()).isEqualTo(3);
        // The voice degraded to the deterministic templated line.
        assertThat(view.coachVoice()).isPresent();
        CoachVoice voice = view.coachVoice().get();
        assertThat(voice.degraded()).isTrue();
        assertThat(voice.source()).isEqualTo(CoachVoice.Source.TEMPLATE);
        assertThat(voice.message()).isEqualTo(template.render(WIG_HIT));
    }

    @Test
    @DisplayName("the Scoreboard read model renders without a voice at all (decoupled)")
    void scoreboard_renders_without_voice() {
        ScoreboardView panel = ScoreboardView.from(WIG_HIT);

        assertThat(panel.hasData()).isTrue();
        assertThat(panel.wigHit()).isTrue();
        assertThat(panel.wigStreak()).isEqualTo(3);
        assertThat(panel.coachVoice()).isEmpty();
    }

    @Test
    @DisplayName("no provider wired → templated line (single degradation frontier)")
    void no_provider_degrades_to_template() {
        CoachVoiceService service = service(queryReturning(Optional.of(WIG_HIT)), null);

        CoachVoice voice = service.voiceFor(USER).orElseThrow();

        assertThat(voice.source()).isEqualTo(CoachVoice.Source.TEMPLATE);
        assertThat(voice.message()).isEqualTo(template.render(WIG_HIT));
    }

    @Test
    @DisplayName("the LLM call is lazy and cached — fired once per signal set, not per render")
    void call_is_cached_across_renders() {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway counting = prompt -> {
            calls.incrementAndGet();
            return "Sigue así.";
        };
        CoachVoiceService service = service(queryReturning(Optional.of(WIG_HIT)), counting);

        service.renderScoreboard(USER);
        service.renderScoreboard(USER);
        service.voiceFor(USER);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a blank completion is treated as a failure and degrades to template")
    void blank_completion_degrades() {
        CoachVoiceService service = service(queryReturning(Optional.of(WIG_HIT)), prompt -> "   ");

        CoachVoice voice = service.voiceFor(USER).orElseThrow();

        assertThat(voice.source()).isEqualTo(CoachVoice.Source.TEMPLATE);
    }
}
