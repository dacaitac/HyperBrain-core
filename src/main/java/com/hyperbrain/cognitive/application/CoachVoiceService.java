package com.hyperbrain.cognitive.application;

import com.hyperbrain.cognitive.domain.model.CoachSignals;
import com.hyperbrain.cognitive.domain.model.CoachVoice;
import com.hyperbrain.cognitive.domain.model.ScoreboardView;
import com.hyperbrain.cognitive.domain.port.out.LlmGateway;
import com.hyperbrain.cognitive.domain.port.out.DailyAdherenceQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Scoreboard coach voice as an asynchronous, lazy, cached read model (ADR-029 D3) — deliberately
 * decoupled from agenda generation (ADR-029 D1): another surface, another trigger, another degradation.
 * Three invariants shape it:
 *
 * <ul>
 *   <li><b>Never blocks the Scoreboard.</b> {@link #renderScoreboard} builds the 4DX panel from the hard
 *       signals first ({@link ScoreboardView#from}), then attaches the voice only if one is available. The
 *       voice path never throws, so a missing, failing or not-yet-computed voice leaves the numbers intact.
 *       It runs outside any generation transaction — a plain read path.</li>
 *   <li><b>Single degradation frontier.</b> One lazy LLM call, anchored to the hard signals; any failure —
 *       no provider wired, transport error, timeout, blank or invalid completion — degrades to the
 *       deterministic {@link CoachVoiceTemplate}. The frontier is {@link #computeVoice}; nothing above it
 *       ever sees a raw failure.</li>
 *   <li><b>Lazy + cached.</b> The call is memoized by the day's signals, so it fires once per distinct
 *       {@link CoachSignals} rather than on every render. When the signals change (a new day, a re-rolled
 *       day) a fresh line is computed; identical signals reuse the cached line.</li>
 * </ul>
 *
 * <p>The gateway is resolved per call through an {@link ObjectProvider}: it exists only when the LLM tier
 * is switched on ({@code app.cognitive.llm-propose.enabled} + a configured provider). Resolving lazily is
 * order-safe (the bean may be contributed after this service is built) and makes "no provider" just another
 * branch of the single degradation frontier — the templated line.
 */
@Service
public class CoachVoiceService {

    private static final Logger log = LoggerFactory.getLogger(CoachVoiceService.class);

    private final DailyAdherenceQuery adherenceQuery;
    private final CoachVoicePromptBuilder promptBuilder;
    private final CoachVoiceTemplate template;
    private final ObjectProvider<LlmGateway> gatewayProvider;

    /** Lazy memoization by the day's hard signals: one LLM call per distinct signal set, not per render. */
    private final Map<CoachSignals, CoachVoice> cache = new ConcurrentHashMap<>();

    public CoachVoiceService(DailyAdherenceQuery adherenceQuery, CoachVoicePromptBuilder promptBuilder,
                             CoachVoiceTemplate template, ObjectProvider<LlmGateway> gatewayProvider) {
        this.adherenceQuery = adherenceQuery;
        this.promptBuilder = promptBuilder;
        this.template = template;
        this.gatewayProvider = gatewayProvider;
    }

    /**
     * Renders the 4DX Scoreboard read model for a user: the hard signals always, the coach voice when
     * available. Never blocks on the voice — the panel is built first and the (cached, degradable) voice is
     * attached if present.
     *
     * @param userId the user to render; never null
     * @return the Scoreboard view, or {@link ScoreboardView#empty()} before the first rollup
     */
    public ScoreboardView renderScoreboard(UUID userId) {
        return adherenceQuery.latestSignals(requireUser(userId))
            .map(signals -> ScoreboardView.from(signals).withVoice(coachVoice(signals)))
            .orElseGet(ScoreboardView::empty);
    }

    /**
     * The coach voice alone (lazy + cached, degradable), or empty when no day has been rolled up yet. The
     * asynchronous surface a client can fetch independently of the panel.
     *
     * @param userId the user; never null
     * @return the coach voice, or {@link Optional#empty()} when there is nothing to speak to
     */
    public Optional<CoachVoice> voiceFor(UUID userId) {
        return adherenceQuery.latestSignals(requireUser(userId)).map(this::coachVoice);
    }

    private CoachVoice coachVoice(CoachSignals signals) {
        return cache.computeIfAbsent(signals, this::computeVoice);
    }

    /**
     * The single degradation frontier: one LLM attempt anchored to the signals, falling back to the
     * deterministic templated line on any failure (absent provider, transport/timeout, blank or invalid
     * completion). Total by construction — it always returns a valid, signal-anchored line.
     */
    private CoachVoice computeVoice(CoachSignals signals) {
        LlmGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return CoachVoice.templated(template.render(signals));
        }
        try {
            String raw = gateway.complete(promptBuilder.build(signals));
            return CoachVoice.fromLlm(raw.strip());
        } catch (RuntimeException ex) {
            log.warn("Coach voice degraded to template for {}: {}", signals.date(), ex.getMessage());
            return CoachVoice.templated(template.render(signals));
        }
    }

    private static UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        return userId;
    }
}
