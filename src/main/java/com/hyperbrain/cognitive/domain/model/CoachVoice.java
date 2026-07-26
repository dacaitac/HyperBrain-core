package com.hyperbrain.cognitive.domain.model;

/**
 * The Scoreboard's coach voice message (ADR-029 D3): a single short line confronting the day's hard
 * signals. Carries its {@link Source} so the read model and telemetry can tell a live-model line from the
 * deterministic fallback — the single degradation frontier of ADR-029: any LLM failure (absent provider,
 * transport error, timeout, blank completion) yields a {@link Source#TEMPLATE} line instead, and the
 * Scoreboard is never blocked nor left silent.
 *
 * @param message the coach line shown to the user; never null nor blank
 * @param source  whether the line came from the live model or the deterministic template; never null
 */
public record CoachVoice(String message, Source source) {

    /** Where a coach line came from — the live LLM, or the deterministic templated fallback. */
    public enum Source { LLM, TEMPLATE }

    public CoachVoice {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
    }

    /** A line produced by the live model. */
    public static CoachVoice fromLlm(String message) {
        return new CoachVoice(message, Source.LLM);
    }

    /** A line produced by the deterministic template — the degradation fallback. */
    public static CoachVoice templated(String message) {
        return new CoachVoice(message, Source.TEMPLATE);
    }

    /** Whether this line is the degraded (templated) fallback rather than a live-model line. */
    public boolean degraded() {
        return source == Source.TEMPLATE;
    }
}
